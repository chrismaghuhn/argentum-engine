package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.mechanics.mana.ManaAbilityIdentity
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.definitions.rav.cards.GolgariSignet
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.ActivatedAbility
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json

/** Contract and publication coverage for the first paid-mana-source V5 slice. */
class PaymentDomainV5ContractTest : FunSpec({

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

    val colorlessFallbackLand = card("PAY106 V5 Colorless Fallback Land") {
        typeLine = "Land"
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
        extra.forEach { moveNamed(it.name, Zone.BATTLEFIELD) }
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

    test("PAY106-11: an unsupported paid source makes the whole V5 domain unsupported") {
        val fixture = prepared(listOf(unsupportedPaidManaSource))
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

    test("V5 resolves the Rules-synthesized colorless fallback source") {
        val fixture = prepared(listOf(colorlessFallbackLand))
        val domain = ObservationBuilder(cardRegistry = fixture.cardRegistry)
            .paymentDomainV5For(fixture.environment.state, fixture.legalAction)!!
        val fallback = domain.sourceActivationOptions.single {
            it.sourceName == colorlessFallbackLand.name
        }

        fallback.manaAbilityKey shouldBe ManaAbilityIdentity.intrinsic(null)
        fallback.atomicActivationManaCostUnits shouldBe emptyList()
        fallback.productionChoices.single().producedColor shouldBe
            com.wingedsheep.engine.core.PaymentManaColor.COLORLESS
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
