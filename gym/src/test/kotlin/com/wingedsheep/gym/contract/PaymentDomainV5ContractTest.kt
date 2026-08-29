package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.FloatingManaBucketKeyV1
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.core.InitialPoolBucketKeyV1
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.event.GrantedStaticAbility
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.handlers.effects.BattlefieldEntry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.mechanics.mana.ManaAbilityIdentity
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.mechanics.mana.PaymentManaSideEffectCertificate
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.mechanics.mana.PaidManaSourceTimingCertifier
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.BattlefieldEntryTimestampComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.ManaProvenanceCompleteness
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.apc.cards.BattlefieldForge
import com.wingedsheep.mtg.sets.definitions.apc.cards.LlanowarWastes
import com.wingedsheep.mtg.sets.definitions.mh1.cards.TalismanOfConviction
import com.wingedsheep.mtg.sets.definitions.mh1.cards.TalismanOfResilience
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.definitions.rav.cards.GolgariSignet
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.CreatureStats
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.ActivationRestriction
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.scripting.Duration
import com.wingedsheep.sdk.scripting.IncreaseActivatedAbilityCost
import com.wingedsheep.sdk.scripting.NoncombatDamageBonus
import com.wingedsheep.sdk.scripting.PlayersCantActivateAbilities
import com.wingedsheep.sdk.scripting.PreventActivatedAbilities
import com.wingedsheep.sdk.scripting.ReduceActivatedAbilityCost
import com.wingedsheep.sdk.scripting.StaticAbility
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.GrantActivatedAbility
import com.wingedsheep.sdk.scripting.TimingRule
import com.wingedsheep.sdk.scripting.effects.ManaRestriction
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json

/** Contract and publication coverage for the first paid-mana-source V5 slice. */
class PaymentDomainV5ContractTest : FunSpec({

    test("current Gym schema identifies the V5 paid-source contract") {
        SchemaHash.CURRENT shouldBe "argentum-gym-contract@v1.23-paid-mana-source-payment"
    }

    val outerSpell = card("PAY106 V5 Outer Spell") {
        manaCost = "{2}{B}{B}"
        typeLine = "Sorcery"
        spell {
            effect = Effects.GainLife(1)
        }
    }

    val unsupportedPaidManaSource = card("PAY106 V5 Unsupported Paid Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Composite(
                Costs.Tap,
                Costs.TapAnotherPermanent(),
            )
            effect = Effects.AddMana(Color.GREEN)
            manaAbility = true
        }
    }

    val innerOnlyManaSource = card("PAY106 V5 Inner-Only Mana Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(
                color = Color.GREEN,
                restriction = ManaRestriction.AbilityActivationOnly,
            )
            manaAbility = true
        }
    }

    val mixedContextManaSource = card("PAY106 V5 Mixed-Context Mana Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(
                color = Color.GREEN,
                restriction = ManaRestriction.AbilityActivationOnly,
            )
            manaAbility = true
        }
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(Color.BLACK)
            manaAbility = true
        }
    }

    /** A mana ability whose legality can change after an earlier ordered node taps a Forest. */
    val sequenceGuardedManaSource = card("PAY106 V5 Sequence Guarded Mana Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(Color.BLACK)
            manaAbility = true
            restrictions = listOf(
                ActivationRestriction.OnlyIfCondition(
                    Conditions.YouControl(GameObjectFilter.Land.untapped())
                )
            )
        }
    }

    /** A mana ability whose effective generic cost changes after an earlier Forest tap. */
    val sequenceCostChangingManaSource = card("PAY106 V5 Sequence Cost Changing Mana Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Composite(Costs.Mana("{1}"), Costs.Tap)
            effect = Effects.AddMana(Color.BLACK)
            manaAbility = true
            genericCostReduction = DynamicAmount.Conditional(
                condition = Conditions.YouControl(GameObjectFilter.Land.untapped()),
                ifTrue = DynamicAmount.Fixed(1),
                ifFalse = DynamicAmount.Fixed(0),
            )
        }
    }

    val permissionTargetManaSource = card("PAY106 V5 Permission Target Mana Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(Color.BLACK)
            manaAbility = true
        }
    }

    /** Tapping this source turns on a live permission lock for the other mana source. */
    val permissionGuardingManaSource = card("PAY106 V5 Permission Guarding Mana Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(Color.GREEN)
            manaAbility = true
        }
        staticAbility {
            ability = PlayersCantActivateAbilities(
                affected = Player.You,
                permanentFilter = GameObjectFilter.Artifact.named(permissionTargetManaSource.name),
                condition = Conditions.EntityMatches(
                    EffectTarget.Self,
                    GameObjectFilter.Any.tapped(),
                ),
            )
        }
    }

    val collidingManaArtifact = card("PAY106 V5 Colliding Mana Artifact") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(Color.GREEN)
            manaAbility = true
        }
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(Color.GREEN)
            manaAbility = true
        }
    }

    val orderedManaArtifact = card("PAY106 V5 Ordered Mana Artifact") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(Color.GREEN)
            manaAbility = true
        }
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(Color.BLACK)
            manaAbility = true
        }
    }

    val grantTestLand = card("PAY106 V5 Grant Test Land") {
        typeLine = "Land — Forest"
    }

    val greenGrantAbility = ActivatedAbility(
        id = AbilityId.generate(),
        cost = Costs.Tap,
        effect = Effects.AddMana(Color.GREEN),
        timing = TimingRule.ManaAbility,
        isManaAbility = true,
    )

    val greenGrantor = card("PAY106 V5 Green Grantor") {
        typeLine = "Enchantment"
        staticAbility {
            ability = GrantActivatedAbility(
                ability = greenGrantAbility,
                filter = GroupFilter(GameObjectFilter.Land.named(grantTestLand.name).youControl()),
            )
        }
    }

    val blackGrantAbility = ActivatedAbility(
        id = AbilityId.generate(),
        cost = Costs.Tap,
        effect = Effects.AddMana(Color.BLACK),
        timing = TimingRule.ManaAbility,
        isManaAbility = true,
    )

    val blackGrantor = card("PAY106 V5 Black Grantor") {
        typeLine = "Enchantment"
        staticAbility {
            ability = GrantActivatedAbility(
                ability = blackGrantAbility,
                filter = GroupFilter(GameObjectFilter.Land.named(grantTestLand.name).youControl()),
            )
        }
    }

    fun registry(extra: List<com.wingedsheep.sdk.model.CardDefinition> = emptyList()) =
        CardRegistry().apply {
            register(PortalSet.cards)
            register(PortalSet.basicLands)
            register(GolgariSignet)
            register(outerSpell)
            extra.forEach(::register)
        }

    data class Fixture(
        val environment: GameEnvironment,
        val cardRegistry: CardRegistry,
        val playerId: EntityId,
        val legalAction: com.wingedsheep.engine.legalactions.LegalAction,
        val signetId: EntityId,
        val forestId: EntityId,
        val swampId: EntityId,
        val extraIds: Map<String, EntityId>,
    )

    fun prepared(extra: List<com.wingedsheep.sdk.model.CardDefinition> = emptyList()): Fixture {
        val cardRegistry = registry(extra)
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of(
                            outerSpell.name to 1,
                            GolgariSignet.name to 1,
                            "Forest" to 2,
                            "Swamp" to 2,
                            "Mountain" to 8,
                            *(extra.map { it.name to 1 }.toTypedArray()),
                        ),
                    ),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 106501L,
            ),
        )

        val player = environment.playerIds.first()
        var state = environment.state
        while (state.step != Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().firstOrNull { it.action is PassPriority }
                ?: error("PAY106 fixture could not find PassPriority at step ${state.step}")
            environment.step(pass.action)
            state = environment.state
        }

        fun moveNamed(name: String, destination: Zone): EntityId {
            val id = state.entities.entries.firstOrNull { (candidate, container) ->
                candidate in state.getZone(player, Zone.HAND) + state.getZone(player, Zone.LIBRARY) &&
                    container.get<CardComponent>()?.name == name
            }?.key ?: error("PAY106 fixture could not locate $name in hand or library")
            val from = state.zones.entries.firstOrNull { (_, ids) -> id in ids }?.key
                ?: error("PAY106 fixture could not find zone for $name ($id)")
            val target = ZoneKey(player, destination)
            if (from != target) state = state.moveToZone(id, from, target)
            return id
        }

        val spellId = moveNamed(outerSpell.name, Zone.HAND)
        val signetId = moveNamed(GolgariSignet.name, Zone.BATTLEFIELD)
        val forestId = moveNamed("Forest", Zone.BATTLEFIELD)
        val swampId = moveNamed("Swamp", Zone.BATTLEFIELD)
        val extraIds = extra.associate { it.name to moveNamed(it.name, Zone.BATTLEFIELD) }
        environment.restore(state, environment.playerIds, environment.stepCount)

        val legalAction = LegalAction(
            action = CastSpell(player, spellId),
            actionType = "CastSpell",
            description = "Cast ${outerSpell.name}",
            affordable = true,
            manaCostString = "{2}{B}{B}",
        )
        return Fixture(
            environment = environment,
            cardRegistry = cardRegistry,
            playerId = player,
            legalAction = legalAction,
            signetId = signetId,
            forestId = forestId,
            swampId = swampId,
            extraIds = extraIds,
        )
    }

    /**
     * Build the same inline-token shape emitted by CreateTokenEffect: the CardComponent carries
     * a token:* identity, TokenComponent marks token-ness, and token statics are represented by
     * the Rules-owned granted-static channel rather than a CardDefinition.
     */
    fun withInlineToken(
        fixture: Fixture,
        cardDefinitionId: String = "token:Beast",
        tokenComponent: Boolean = true,
        staticAbilities: List<StaticAbility> = emptyList(),
    ): GameState {
        val (tokenId, stateWithId) = fixture.environment.state.newEntity()
        val tokenCard = CardComponent(
            cardDefinitionId = cardDefinitionId,
            name = "Beast Token",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine.parse("Creature — Beast"),
            baseStats = CreatureStats(3, 3),
            ownerId = fixture.playerId,
        )
        val components = buildList {
            add(tokenCard)
            if (tokenComponent) add(TokenComponent)
            add(ControllerComponent(fixture.playerId))
        }
        var state = BattlefieldEntry.place(
            stateWithId.withEntity(tokenId, ComponentContainer.of(*components.toTypedArray())),
            fixture.playerId,
            tokenId,
        )
        if (staticAbilities.isNotEmpty()) {
            state = state.copy(
                grantedStaticAbilities = state.grantedStaticAbilities + staticAbilities.map { ability ->
                    GrantedStaticAbility(
                        entityId = tokenId,
                        ability = ability,
                        duration = Duration.Permanent,
                    )
                },
            )
        }
        return state
    }

    fun tokenCostModifier(): ReduceActivatedAbilityCost = ReduceActivatedAbilityCost(
        filter = GroupFilter(GameObjectFilter.Any),
        amount = DynamicAmount.Fixed(1),
    )

    fun tokenCostIncrease(): IncreaseActivatedAbilityCost = IncreaseActivatedAbilityCost(
        filter = GroupFilter(GameObjectFilter.Any),
        amount = DynamicAmount.Fixed(1),
    )

    fun tokenActivationPermission(): PlayersCantActivateAbilities = PlayersCantActivateAbilities()

    fun tokenActivationPrevention(): PreventActivatedAbilities = PreventActivatedAbilities(
        filter = GameObjectFilter.Any,
    )

    test("PAY106-TOKEN-STABILITY-01: a plain inline token does not close V5 stability") {
        val fixture = prepared()
        val state = withInlineToken(fixture)

        val domain = ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .paymentDomainV5For(state, fixture.legalAction)

        domain shouldNotBe null
    }

    test("PAY106-TOKEN-STABILITY-02: an inline token cost reducer remains unsupported") {
        val fixture = prepared()
        val state = withInlineToken(fixture, staticAbilities = listOf(tokenCostModifier()))

        ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .paymentDomainV5For(state, fixture.legalAction) shouldBe null
    }

    test("PAY106-TOKEN-STABILITY-03: an inline token cost increase remains unsupported") {
        val fixture = prepared()
        val state = withInlineToken(fixture, staticAbilities = listOf(tokenCostIncrease()))

        ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .paymentDomainV5For(state, fixture.legalAction) shouldBe null
    }

    test("PAY106-TOKEN-STABILITY-04: an inline token activation lock remains unsupported") {
        val fixture = prepared()
        val state = withInlineToken(fixture, staticAbilities = listOf(tokenActivationPermission()))

        ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .paymentDomainV5For(state, fixture.legalAction) shouldBe null

        val preventionState = withInlineToken(
            fixture,
            staticAbilities = listOf(tokenActivationPrevention()),
        )
        ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .paymentDomainV5For(preventionState, fixture.legalAction) shouldBe null
    }

    test("PAY106-TOKEN-STABILITY-05: an unknown non-token remains fail-closed") {
        val fixture = prepared()
        val state = withInlineToken(
            fixture,
            cardDefinitionId = "unknown:missing-definition",
            tokenComponent = false,
        )

        ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .paymentDomainV5For(state, fixture.legalAction) shouldBe null
    }

    test("PAY106-TOKEN-STABILITY-06: token damage amplification closes pain certification") {
        val fixture = prepared(listOf(LlanowarWastes))
        val state = withInlineToken(
            fixture,
            staticAbilities = listOf(NoncombatDamageBonus(1)),
        )

        ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .paymentDomainV5For(state, fixture.legalAction) shouldBe null
    }

    fun certifiedJointPool(
        fixture: Fixture,
        reverseInsertionOrder: Boolean = false,
    ): ManaPoolComponent {
        val sourceId = fixture.forestId
        val blackKey = FloatingManaBucketKeyV1(
            sourceId = sourceId,
            poolColor = PaymentManaColor.BLACK,
            sourceSubtypes = emptySet(),
        )
        val greenKey = FloatingManaBucketKeyV1(
            sourceId = sourceId,
            poolColor = PaymentManaColor.GREEN,
            sourceSubtypes = emptySet(),
        )
        val buckets = if (reverseInsertionOrder) {
            linkedMapOf(greenKey to 1, blackKey to 1)
        } else {
            linkedMapOf(blackKey to 1, greenKey to 1)
        }
        val sourceColors = if (reverseInsertionOrder) {
            linkedMapOf(PaymentManaColor.GREEN to 1, PaymentManaColor.BLACK to 1)
        } else {
            linkedMapOf(PaymentManaColor.BLACK to 1, PaymentManaColor.GREEN to 1)
        }
        return ManaPoolComponent(
            black = 1,
            green = 1,
            manaBySource = mapOf(sourceId to 2),
            manaBySourceAndColor = mapOf(sourceId to sourceColors),
            manaByFloatingBucket = buckets,
            manaProvenanceCompleteness = ManaProvenanceCompleteness.COMPLETE,
            manaProvenanceKnownTo = setOf(fixture.playerId),
        )
    }

    test("PAY106-01: Forest plus Golgari Signet publishes a complete V5 domain") {
        val fixture = prepared()
        val builder = ObservationBuilder(cardRegistry = fixture.cardRegistry)

        val domain = builder.paymentDomainV5For(fixture.environment.state, fixture.legalAction)

        domain shouldNotBe null
        domain!!.requiredCost shouldBe "{2}{B}{B}"
        domain.outerAtomicCostUnits.map { it.kind.name } shouldBe
            listOf("GENERIC", "GENERIC", "COLORED", "COLORED")
        domain.sourceActivationOptions.map { it.sourceId } shouldContain fixture.signetId
        domain.sourceActivationOptions.map { it.sourceId } shouldContain fixture.forestId
        domain.sourceActivationOptions.map { it.sourceId } shouldContain fixture.swampId
    }

    test("current Gym observations carry the V5 payment domain") {
        val fixture = prepared()
        val observation = ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .build(
                state = fixture.environment.state,
                perspectivePlayerId = fixture.playerId,
                legalActions = listOf(fixture.legalAction),
            )
            .observation as TrainingObservation

        val action = observation.legalActions.single()
        action.paymentDomain shouldNotBe null
        action.paymentDomain!!.version shouldBe PAYMENT_DOMAIN_V5_VERSION
        action.paymentDomain!!.sourceActivationOptions
            .map { it.sourceId } shouldContain fixture.signetId
    }

    test("PAY106-02: Signet publishes its effective activation cost and fixed B/G bundle") {
        val fixture = prepared()
        val domain = ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .paymentDomainV5For(fixture.environment.state, fixture.legalAction)!!
        val signet = domain.sourceActivationOptions.single { it.sourceId == fixture.signetId }
        val expectedAbility = GolgariSignet.activatedAbilities.single()

        signet.manaAbilityKey shouldBe ManaAbilityIdentity.key(expectedAbility)
        signet.atomicActivationManaCostUnits.single().kind.name shouldBe "GENERIC"
        signet.atomicActivationManaCostUnits.single().symbolIndex shouldBe 0
        signet.activationSupportKind.name shouldBe "FIXED_MANA_AND_TAP_SELF"
        signet.deterministicNonManaCosts.map { it.name } shouldBe listOf("TAP_SELF")
        signet.activationCostOrderOptions.single().map { it::class.simpleName } shouldBe
            listOf("ManaComponent", "DeterministicNonManaComponent")
        signet.productionChoices.single().fixedOutputs?.map { it.color } shouldBe
            listOf(
                com.wingedsheep.engine.core.PaymentManaColor.BLACK,
                com.wingedsheep.engine.core.PaymentManaColor.GREEN,
        )
    }

    test("PAY106-LETHAL-PAIN-01: V5 publishes pain at life one without an outer life reservation") {
        val fixture = prepared(listOf(LlanowarWastes))
        val state = fixture.environment.state.withLifeTotal(fixture.playerId, 1)
        val domain = ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .paymentDomainV5For(state, fixture.legalAction)

        domain shouldNotBe null
        domain!!.reservedOuterLifePayment shouldBe 0
        domain.fixedSelfDamageBudget shouldBe null
        val wastesId = fixture.extraIds[LlanowarWastes.name]
            ?: error("PAY106 fixture did not capture Llanowar Wastes")
        domain.sourceActivationOptions
            .filter { it.sourceId == wastesId && it.fixedSelfDamageAmount == 1 }
            .size shouldBe 2
    }

    test("PAY106-SIDEEFFECT-01: locked-deck pain sources publish complete V5 domains") {
        for (painSource in listOf(
            BattlefieldForge,
            LlanowarWastes,
            TalismanOfConviction,
            TalismanOfResilience,
        )) {
            val fixture = prepared(listOf(painSource))
            val sourceId = fixture.extraIds[painSource.name]
                ?: error("PAY106 fixture did not capture ${painSource.name}")
            val discovered = ManaSolver(fixture.cardRegistry).findAvailableManaSources(
                state = fixture.environment.state,
                playerId = fixture.playerId,
                spellContext = null,
                paymentOrderRequired = true,
            )
            val source = discovered.single { it.entityId == sourceId }
            source.paymentManaSideEffectCertificates.values.any {
                it is PaymentManaSideEffectCertificate.FixedSelfDamage
            } shouldBe true

            val domain = ObservationBuilder(cardRegistry = fixture.cardRegistry)
                .paymentDomainV5For(fixture.environment.state, fixture.legalAction)
                .shouldNotBe(null)
            val published = domain!!.sourceActivationOptions
                .filter { it.sourceId == sourceId }
            val publishedKeys = published.map { it.manaAbilityKey }
            publishedKeys shouldBe source.paymentManaAbilityOrder
            published.size shouldBe source.paymentManaProductionProfiles.size
            source.paymentManaSideEffectCertificates
                .filterValues { it is PaymentManaSideEffectCertificate.FixedSelfDamage }
                .keys
                .forEach { painKey -> publishedKeys shouldContain painKey }
        }
    }

    test("PAY106-MANA-WINDOW-01: unavailable timing certification rejects a Signet-shaped source") {
        val fixture = prepared()
        val domain = ObservationBuilder(
            cardRegistry = fixture.cardRegistry,
            paidManaSourceTimingCertifier = PaidManaSourceTimingCertifier { false },
        ).paymentDomainV5For(fixture.environment.state, fixture.legalAction)

        domain shouldBe null
    }

    test("PAY106-CONTEXT-01: V5 cannot omit a source usable for a paid activation") {
        val fixture = prepared(listOf(innerOnlyManaSource))
        val domain = ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .paymentDomainV5For(fixture.environment.state, fixture.legalAction)

        domain shouldBe null
    }

    test("PAY106-CONTEXT-02: V5 rejects a mixed restricted source after complete discovery") {
        val fixture = prepared(listOf(mixedContextManaSource))
        val domain = ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .paymentDomainV5For(fixture.environment.state, fixture.legalAction)

        domain shouldBe null
    }

    test("PAY106-EXECUTOR-SEQ-01: V5 rejects a source whose legality can change after an earlier node") {
        val fixture = prepared(listOf(sequenceGuardedManaSource))
        val domain = ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .paymentDomainV5For(fixture.environment.state, fixture.legalAction)

        domain shouldBe null
    }

    test("PAY106-EXECUTOR-SEQ-02: V5 rejects a source whose effective cost can change after an earlier node") {
        val fixture = prepared(listOf(sequenceCostChangingManaSource))
        val domain = ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .paymentDomainV5For(fixture.environment.state, fixture.legalAction)

        domain shouldBe null
    }

    test("PAY106-EXECUTOR-STABILITY-03: V5 rejects external activation-permission closure") {
        val fixture = prepared(listOf(permissionGuardingManaSource, permissionTargetManaSource))
        val domain = ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .paymentDomainV5For(fixture.environment.state, fixture.legalAction)

        domain shouldBe null
    }

    test("PAY106-11: an unsupported paid source makes the whole V5 domain unsupported") {
        val fixture = prepared(listOf(unsupportedPaidManaSource))
        val domain = ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .paymentDomainV5For(fixture.environment.state, fixture.legalAction)

        domain shouldBe null
    }

    test("PAY106-FLOATING-JOINT-01: V5 publishes every certified joint pool bucket") {
        val fixture = prepared()
        val pool = certifiedJointPool(fixture)
        val stateWithPool = fixture.environment.state.updateEntity(fixture.playerId) {
            it.with(pool)
        }

        val domain = ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .paymentDomainV5For(stateWithPool, fixture.legalAction)

        domain shouldNotBe null
        domain!!.initialPoolBuckets.map { it.key } shouldBe listOf(
            InitialPoolBucketKeyV1.CertifiedFloatingBucket(
                FloatingManaBucketKeyV1(
                    sourceId = fixture.forestId,
                    poolColor = PaymentManaColor.BLACK,
                    sourceSubtypes = emptySet(),
                ),
            ),
            InitialPoolBucketKeyV1.CertifiedFloatingBucket(
                FloatingManaBucketKeyV1(
                    sourceId = fixture.forestId,
                    poolColor = PaymentManaColor.GREEN,
                    sourceSubtypes = emptySet(),
                ),
            ),
        )
        domain.initialPoolBuckets.map { it.availableAmount } shouldBe listOf(1, 1)
    }

    test("PAY106-FLOATING-JOINT-05: keyed joint buckets have canonical public ordering") {
        val fixture = prepared()
        val state = fixture.environment.state
        val firstState = state.updateEntity(fixture.playerId) {
            it.with(certifiedJointPool(fixture))
        }
        val secondState = state.updateEntity(fixture.playerId) {
            it.with(certifiedJointPool(fixture, reverseInsertionOrder = true))
        }
        val builder = ObservationBuilder(cardRegistry = fixture.cardRegistry)
        val firstDomain = builder.paymentDomainV5For(firstState, fixture.legalAction)
        val secondDomain = builder.paymentDomainV5For(secondState, fixture.legalAction)

        firstDomain shouldNotBe null
        secondDomain shouldNotBe null
        firstDomain shouldBe secondDomain

        val firstObservation = builder.build(
            state = firstState,
            perspectivePlayerId = fixture.playerId,
            legalActions = listOf(fixture.legalAction),
        ).observation as TrainingObservation
        val secondObservation = builder.build(
            state = secondState,
            perspectivePlayerId = fixture.playerId,
            legalActions = listOf(fixture.legalAction),
        ).observation as TrainingObservation

        ObservationCanonicalizer.semanticJson(firstObservation) shouldBe
            ObservationCanonicalizer.semanticJson(secondObservation)
        ObservationCanonicalizer.wireJson(firstObservation) shouldBe
            ObservationCanonicalizer.wireJson(secondObservation)
        firstObservation.stateDigest shouldBe secondObservation.stateDigest
    }

    test("V5 rejects the pre-existing synthetic colorless land fallback") {
        val blankLand = card("PAY106 V5 Blank Land") {
            typeLine = "Land"
        }
        val fixture = prepared(listOf(blankLand))

        val domain = ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .paymentDomainV5For(fixture.environment.state, fixture.legalAction)

        domain shouldBe null
    }

    test("PAY106-KEY-01: colliding structural ability keys fail the complete-source gate") {
        val fixture = prepared(listOf(collidingManaArtifact))
        val domain = ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .paymentDomainV5For(fixture.environment.state, fixture.legalAction)

        domain shouldBe null
    }

    test("V5 preserves the Rules-owned ability presentation order") {
        val fixture = prepared(listOf(orderedManaArtifact))
        val domain = ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .paymentDomainV5For(fixture.environment.state, fixture.legalAction)!!
        val publishedKeys = domain.sourceActivationOptions
            .filter { it.sourceName == orderedManaArtifact.name }
            .map { it.manaAbilityKey }

        publishedKeys shouldBe orderedManaArtifact.activatedAbilities.map(ManaAbilityIdentity::key)
    }

    test("V5 orders statically granted mana abilities by Rules object rank") {
        val fixture = prepared(listOf(grantTestLand, greenGrantor, blackGrantor))
        val battlefieldKey = ZoneKey(fixture.playerId, Zone.BATTLEFIELD)
        val battlefield = fixture.environment.state.zones[battlefieldKey]
            ?: error("PAY106 fixture has no battlefield")
        val reversedState = fixture.environment.state.copy(
            zones = fixture.environment.state.zones + (battlefieldKey to battlefield.reversed()),
        )

        val domain = ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .paymentDomainV5For(reversedState, fixture.legalAction)!!
        val forestKeys = domain.sourceActivationOptions
            .filter { it.sourceId == fixture.extraIds[grantTestLand.name] }
            .map { it.manaAbilityKey }

        forestKeys shouldBe listOf(
            ManaAbilityIdentity.intrinsic(Color.GREEN),
            ManaAbilityIdentity.key(greenGrantAbility),
            ManaAbilityIdentity.key(blackGrantAbility),
        )
    }

    test("V5 rejects statically granted abilities when object ranks are missing or duplicated") {
        val fixture = prepared(listOf(grantTestLand, greenGrantor, blackGrantor))
        val greenGrantorId = fixture.extraIds[greenGrantor.name]
            ?: error("PAY106 fixture did not capture the green grantor")
        val blackGrantorId = fixture.extraIds[blackGrantor.name]
            ?: error("PAY106 fixture did not capture the black grantor")
        val state = fixture.environment.state
        val ranklessState = state
            .copy(objectIdentityStamps = state.objectIdentityStamps - greenGrantorId - blackGrantorId)
            .updateEntity(greenGrantorId) { it.without<BattlefieldEntryTimestampComponent>() }
            .updateEntity(blackGrantorId) { it.without<BattlefieldEntryTimestampComponent>() }
        val greenRank = state.objectIdentityStamps[greenGrantorId]
            ?: error("PAY106 fixture did not assign a green grantor object rank")
        val duplicateRankState = state.copy(
            objectIdentityStamps = state.objectIdentityStamps + (blackGrantorId to greenRank),
        )

        for (candidateState in listOf(ranklessState, duplicateRankState)) {
            val domain = ObservationBuilder(cardRegistry = fixture.cardRegistry)
                .paymentDomainV5For(candidateState, fixture.legalAction)

            domain shouldBe null
        }
    }

    test("activation cost order options must contain every declared component exactly once") {
        shouldThrow<IllegalArgumentException> {
            PaymentSourceActivationDomainV2(
                sourceId = EntityId("source"),
                sourceName = "source",
                manaAbilityKey = "ability",
                productionChoices = listOf(
                    com.wingedsheep.engine.core.ProductionChoice(
                        producedColor = com.wingedsheep.engine.core.PaymentManaColor.GREEN,
                    ),
                ),
                atomicActivationManaCostUnits = listOf(
                    com.wingedsheep.engine.core.AtomicManaCostUnitV1(
                        symbolIndex = 0,
                        unitIndexWithinSymbol = 0,
                        kind = com.wingedsheep.engine.core.PaymentCostKindV1.GENERIC,
                    ),
                ),
                activationSupportKind = PaymentActivationSupportKindV1.FIXED_MANA_AND_TAP_SELF,
                deterministicNonManaCosts = listOf(PaymentDeterministicNonManaCostKindV1.TAP_SELF),
                activationCostOrderOptions = listOf(
                    listOf(com.wingedsheep.engine.core.ActivationCostComponentRefV1.ManaComponent),
                ),
            )
        }
    }

    test("V5 domain wire shape round-trips without a Gym identity field") {
        val fixture = prepared()
        val domain = ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .paymentDomainV5For(fixture.environment.state, fixture.legalAction)!!
        val json = Json {
            serializersModule = engineSerializersModule
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }

        val encoded = json.encodeToString(PaymentDomainV5.serializer(), domain)
        json.decodeFromString(PaymentDomainV5.serializer(), encoded) shouldBe domain
        encoded.contains("domainIdentity") shouldBe false
    }

    test("V5 preserves action-source exclusion for a paid activation's deterministic tap cost") {
        val fixture = prepared()
        val signetAbility = GolgariSignet.activatedAbilities.single()
        val legalAction = LegalAction(
            action = ActivateAbility(
                playerId = fixture.playerId,
                sourceId = fixture.signetId,
                abilityId = signetAbility.id,
            ),
            actionType = "ActivateAbility",
            description = "Activate Golgari Signet",
            affordable = true,
            manaCostString = "{1}",
        )

        val domain = ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .paymentDomainV5For(fixture.environment.state, legalAction)!!

        domain.sourceActivationOptions.any { it.sourceId == fixture.signetId } shouldBe false
        domain.sourceActivationOptions.any { it.sourceId == fixture.forestId } shouldBe true
    }

})
