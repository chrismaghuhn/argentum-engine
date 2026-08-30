package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.ActivationCostComponentRefV1
import com.wingedsheep.engine.core.AtomicManaCostUnitV1
import com.wingedsheep.engine.core.FixedManaOutput
import com.wingedsheep.engine.core.FloatingManaBucketKeyV1
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.InitialPoolBucketKeyV1
import com.wingedsheep.engine.core.InitialPoolBucketV1
import com.wingedsheep.engine.core.PaymentCostKindV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class StateDigestTest : FunSpec({

    fun registry(): CardRegistry = CardRegistry().also {
        it.register(PortalSet.cards)
        it.register(PortalSet.basicLands)
    }

    fun environment(): GameEnvironment {
        val env = GameEnvironment.create(registry())
        env.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 20)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20))
                ),
                skipMulligans = true,
                startingPlayerIndex = 0
            )
        )
        return env
    }

    fun observation(env: GameEnvironment, perspectiveIndex: Int = 0): TrainingObservation =
        ObservationBuilder(cardRegistry = registry()).build(
            env.state,
            env.playerIds[perspectiveIndex],
            env.legalActions()
        ).observation as TrainingObservation

    test("schema identity is digest-relevant") {
        val base = observation(environment())
        val otherSchema = base.copy(schemaHash = "argentum-gym-contract@test-schema")

        StateDigest.compute(base) shouldNotBe StateDigest.compute(otherSchema)
    }

    test("action and decision transport handles are digest-irrelevant") {
        val env = environment()
        val base = observation(env)
        val actionVariant = base.copy(
            legalActions = base.legalActions.mapIndexed { index, action ->
                action.copy(actionId = index + 1000, description = "different text")
            }
        )

        StateDigest.compute(base) shouldBe StateDigest.compute(actionVariant)

        val owner = env.playerIds[0]
        val sourceId = env.state.getHand(owner).first()
        val pendingState = env.state.copy(
            pendingDecision = YesNoDecision(
                id = "decision-original",
                playerId = owner,
                prompt = "private prompt",
                context = DecisionContext(
                    sourceId = sourceId,
                    sourceName = "Mountain",
                    triggeringEntityId = sourceId,
                    effectHint = "private hint"
                )
            )
        )
        val pending = ObservationBuilder(cardRegistry = registry()).build(pendingState, owner, emptyList())
            .observation as TrainingObservation
        val pendingVariant = pending.copy(
            pendingDecision = pending.pendingDecision!!.copy(decisionId = "decision-next")
        )

        StateDigest.compute(pending) shouldBe StateDigest.compute(pendingVariant)
    }

    test("structured legal-action semantics are digest-relevant") {
        val base = observation(environment())
        val first = base.legalActions.first()
        val changed = base.copy(
            legalActions = listOf(first.copy(manaCost = "{9}")) + base.legalActions.drop(1)
        )

        StateDigest.compute(base) shouldNotBe StateDigest.compute(changed)
    }

    test("attack declaration domain semantics are digest-relevant") {
        val base = observation(environment())
        val attacker = EntityId("digest-attacker")
        val defender = EntityId("digest-defender")
        val domain = AttackDeclarationDomainV2(
            attackerOrder = listOf(attacker),
            attackerToDefenders = mapOf(attacker to listOf(defender)),
            mandatoryAttackers = listOf(attacker),
            canDeclareZeroAttackers = false,
            maxAttackers = 1,
            coAttackerRequirements = emptyMap(),
            bandConstraints = AttackBandConstraintsV1(
                bandingAttackersByDefender = emptyMap(),
                nonBandingAttackersByDefender = mapOf(defender to listOf(attacker)),
            ),
        )
        val candidate = LegalActionView(
            actionId = 9003,
            kind = "DeclareAttackers",
            description = "Declare attackers",
            affordable = true,
            requiresStructuredAction = true,
            requiredPayloadFields = listOf("attackers", "bands"),
        )
        val withoutDomain = base.copy(legalActions = base.legalActions + candidate)
        val withDomain = withoutDomain.copy(
            legalActions = withoutDomain.legalActions.map { action ->
                if (action.actionId != candidate.actionId) action else action.copy(
                    attackDeclarationDomain = domain,
                )
            },
        )

        StateDigest.compute(withDomain) shouldNotBe StateDigest.compute(withoutDomain)
        StateDigest.compute(withDomain) shouldNotBe StateDigest.compute(
            withDomain.copy(
                legalActions = withDomain.legalActions.map { action ->
                    if (action.actionId != candidate.actionId) action else action.copy(
                        attackDeclarationDomain = domain.copy(maxAttackers = null),
                    )
                },
            )
        )
    }

    test("required payload fields are digest-relevant") {
        val base = observation(environment())
        val first = base.legalActions.first()
        val withoutFields = base.copy(
            legalActions = listOf(
                first.copy(
                    requiresStructuredAction = true,
                    requiredPayloadFields = emptyList(),
                )
            ) + base.legalActions.drop(1)
        )
        val withFields = base.copy(
            legalActions = listOf(
                first.copy(
                    requiresStructuredAction = true,
                    requiredPayloadFields = listOf("paymentStrategy", "additionalCostPayment"),
                )
            ) + base.legalActions.drop(1)
        )

        StateDigest.compute(withoutFields) shouldNotBe StateDigest.compute(withFields)
    }

    test("published mana color domains are digest-relevant and canonically ordered") {
        val base = observation(environment())
        val candidate = base.legalActions.first()
        val redWhite = base.copy(
            legalActions = listOf(
                candidate.copy(availableManaColors = listOf(Color.RED, Color.WHITE)),
            ) + base.legalActions.drop(1),
        )
        val whiteRed = redWhite.copy(
            legalActions = listOf(
                candidate.copy(availableManaColors = listOf(Color.WHITE, Color.RED)),
            ) + base.legalActions.drop(1),
        )
        val greenOnly = redWhite.copy(
            legalActions = listOf(
                candidate.copy(availableManaColors = listOf(Color.GREEN)),
            ) + base.legalActions.drop(1),
        )

        StateDigest.compute(redWhite) shouldBe StateDigest.compute(whiteRed)
        StateDigest.compute(redWhite) shouldNotBe StateDigest.compute(greenOnly)
    }

    test("CastSpell payment domains are digest-relevant") {
        val base = observation(environment())
        val candidate = LegalActionView(
            actionId = 9001,
            kind = "CastSpell",
            description = "Cast ordinary spell",
            affordable = true,
            manaCost = "{1}{B}",
            requiresStructuredAction = true,
        )
        val withoutDomain = base.copy(legalActions = base.legalActions + candidate)
        val withDomain = withoutDomain.copy(
            legalActions = withoutDomain.legalActions.map { action ->
                if (action.actionId != candidate.actionId) action else action.copy(
                    paymentDomain = PaymentDomainV5(
                        requiredCost = "{1}{B}",
                        outerAtomicCostUnits = listOf(
                            AtomicManaCostUnitV1(
                                symbolIndex = 0,
                                unitIndexWithinSymbol = 0,
                                kind = PaymentCostKindV1.GENERIC,
                            ),
                            AtomicManaCostUnitV1(
                                symbolIndex = 1,
                                unitIndexWithinSymbol = 0,
                                kind = PaymentCostKindV1.COLORED,
                                allowedColors = setOf(PaymentManaColor.BLACK),
                            ),
                        ),
                        initialPoolBuckets = emptyList(),
                        sourceActivationOptions = emptyList(),
                    ),
                )
            },
        )

        StateDigest.compute(withDomain) shouldNotBe StateDigest.compute(withoutDomain)
    }

    test("fixed output order is digest-relevant") {
        val base = observation(environment())
        val candidate = LegalActionView(
            actionId = 9002,
            kind = "CastSpell",
            description = "Cast bundle spell",
            affordable = true,
            manaCost = "{B}",
            requiresStructuredAction = true,
        )
        val domain = PaymentDomainV5(
            requiredCost = "{B}",
            outerAtomicCostUnits = listOf(
                AtomicManaCostUnitV1(
                    symbolIndex = 0,
                    unitIndexWithinSymbol = 0,
                    kind = PaymentCostKindV1.COLORED,
                    allowedColors = setOf(PaymentManaColor.BLACK),
                ),
            ),
            initialPoolBuckets = emptyList(),
            sourceActivationOptions = listOf(
                PaymentSourceActivationDomainV2(
                    sourceId = com.wingedsheep.sdk.model.EntityId("bundle-source"),
                    sourceName = "Bundle Source",
                    manaAbilityKey = "bundle-ability",
                    productionChoices = listOf(
                        ProductionChoice(
                            producedColor = PaymentManaColor.BLACK,
                            fixedOutputs = listOf(
                                FixedManaOutput(0, PaymentManaColor.BLACK),
                                FixedManaOutput(1, PaymentManaColor.GREEN),
                            ),
                        ),
                    ),
                    atomicActivationManaCostUnits = emptyList(),
                    activationSupportKind = PaymentActivationSupportKindV1.FixedManaAndTapSelf,
                    deterministicNonManaCosts = listOf(PaymentDeterministicNonManaCostKindV1.TapSelf),
                    activationCostOrderOptions = listOf(
                        listOf(ActivationCostComponentRefV1.DeterministicNonManaComponent(0)),
                    ),
                ),
            ),
        )
        val ordered = base.copy(
            legalActions = base.legalActions + candidate.copy(paymentDomain = domain),
        )
        val reversed = base.copy(
            legalActions = base.legalActions + candidate.copy(
                paymentDomain = domain.copy(
                    sourceActivationOptions = listOf(
                        domain.sourceActivationOptions.single().copy(
                            productionChoices = listOf(
                                ProductionChoice(
                                    producedColor = PaymentManaColor.GREEN,
                                    fixedOutputs = listOf(
                                        FixedManaOutput(0, PaymentManaColor.GREEN),
                                        FixedManaOutput(1, PaymentManaColor.BLACK),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        StateDigest.compute(ordered) shouldNotBe StateDigest.compute(reversed)
    }

    test("certified floating subtype provenance is digest-relevant") {
        val base = observation(environment())
        val candidate = LegalActionView(
            actionId = 9003,
            kind = "CastSpell",
            description = "Cast certified floating spell",
            affordable = true,
            manaCost = "{G}",
            requiresStructuredAction = true,
        )
        val domain = PaymentDomainV5(
            requiredCost = "{G}",
            outerAtomicCostUnits = listOf(
                AtomicManaCostUnitV1(
                    symbolIndex = 0,
                    unitIndexWithinSymbol = 0,
                    kind = PaymentCostKindV1.COLORED,
                    allowedColors = setOf(PaymentManaColor.GREEN),
                ),
            ),
            initialPoolBuckets = listOf(
                InitialPoolBucketV1(
                    key = InitialPoolBucketKeyV1.CertifiedFloatingBucket(
                        FloatingManaBucketKeyV1(
                            sourceId = com.wingedsheep.sdk.model.EntityId("forest-source"),
                            poolColor = PaymentManaColor.GREEN,
                            sourceSubtypes = setOf(
                                com.wingedsheep.sdk.core.Subtype.CAVE,
                                com.wingedsheep.sdk.core.Subtype.FOREST,
                            ),
                        ),
                    ),
                    availableAmount = 1,
                ),
            ),
            sourceActivationOptions = emptyList(),
        )
        val first = base.copy(legalActions = base.legalActions + candidate.copy(paymentDomain = domain))
        val second = first.copy(
            legalActions = first.legalActions.map { action ->
                if (action.actionId != candidate.actionId) action else action.copy(
                    paymentDomain = domain.copy(
                        initialPoolBuckets = listOf(
                            InitialPoolBucketV1(
                                key = InitialPoolBucketKeyV1.CertifiedFloatingBucket(
                                    FloatingManaBucketKeyV1(
                                        sourceId = com.wingedsheep.sdk.model.EntityId("forest-source"),
                                        poolColor = PaymentManaColor.GREEN,
                                        sourceSubtypes = setOf(com.wingedsheep.sdk.core.Subtype.FOREST),
                                    ),
                                ),
                                availableAmount = 1,
                            ),
                        ),
                    ),
                )
            },
        )

        StateDigest.compute(first) shouldNotBe StateDigest.compute(second)

    }

    test("V5 source candidate order is digest-relevant") {
        val base = observation(environment())
        val candidate = LegalActionView(
            actionId = 9004,
            kind = "CastSpell",
            description = "Cast ordered-source spell",
            affordable = true,
            manaCost = "{1}",
            requiresStructuredAction = true,
        )
        fun option(sourceId: String, abilityKey: String) = PaymentSourceActivationDomainV2(
            sourceId = EntityId(sourceId),
            sourceName = "Source $sourceId",
            manaAbilityKey = abilityKey,
            productionChoices = listOf(ProductionChoice(PaymentManaColor.GREEN)),
            atomicActivationManaCostUnits = emptyList(),
            activationSupportKind = PaymentActivationSupportKindV1.FixedManaAndTapSelf,
            deterministicNonManaCosts = listOf(PaymentDeterministicNonManaCostKindV1.TapSelf),
            activationCostOrderOptions = listOf(
                listOf(ActivationCostComponentRefV1.DeterministicNonManaComponent(0)),
            ),
        )
        val domain = PaymentDomainV5(
            requiredCost = "{1}",
            outerAtomicCostUnits = listOf(
                AtomicManaCostUnitV1(0, 0, PaymentCostKindV1.GENERIC),
            ),
            initialPoolBuckets = emptyList(),
            sourceActivationOptions = listOf(
                option("e108", "ability-a"),
                option("e117", "ability-b"),
            ),
        )
        val first = base.copy(legalActions = base.legalActions + candidate.copy(paymentDomain = domain))
        val reordered = first.copy(
            legalActions = first.legalActions.map { action ->
                if (action.actionId != candidate.actionId) action else action.copy(
                    paymentDomain = domain.copy(sourceActivationOptions = domain.sourceActivationOptions.reversed()),
                )
            },
        )

        StateDigest.compute(first) shouldNotBe StateDigest.compute(reordered)
    }

    test("V5 initial-pool resource identity is digest-relevant") {
        val base = observation(environment())
        val candidate = LegalActionView(
            actionId = 9005,
            kind = "CastSpell",
            description = "Cast certified floating spell",
            affordable = true,
            manaCost = "{G}",
            requiresStructuredAction = true,
        )
        val domain = PaymentDomainV5(
            requiredCost = "{G}",
            outerAtomicCostUnits = listOf(
                AtomicManaCostUnitV1(
                    symbolIndex = 0,
                    unitIndexWithinSymbol = 0,
                    kind = PaymentCostKindV1.COLORED,
                    allowedColors = setOf(PaymentManaColor.GREEN),
                ),
            ),
            initialPoolBuckets = listOf(
                InitialPoolBucketV1(
                    key = InitialPoolBucketKeyV1.CertifiedFloatingBucket(
                        FloatingManaBucketKeyV1(
                            sourceId = EntityId("forest-source"),
                            poolColor = PaymentManaColor.GREEN,
                            sourceSubtypes = emptySet(),
                        ),
                    ),
                    availableAmount = 1,
                ),
            ),
            sourceActivationOptions = emptyList(),
        )
        val first = base.copy(legalActions = base.legalActions + candidate.copy(paymentDomain = domain))
        val changed = first.copy(
            legalActions = first.legalActions.map { action ->
                if (action.actionId != candidate.actionId) action else action.copy(
                    paymentDomain = domain.copy(
                        initialPoolBuckets = listOf(
                            InitialPoolBucketV1(
                                key = InitialPoolBucketKeyV1.CertifiedFloatingBucket(
                                    FloatingManaBucketKeyV1(
                                        sourceId = EntityId("different-source"),
                                        poolColor = PaymentManaColor.GREEN,
                                        sourceSubtypes = emptySet(),
                                    ),
                                ),
                                availableAmount = 1,
                            ),
                        ),
                    ),
                )
            },
        )

        StateDigest.compute(first) shouldNotBe StateDigest.compute(changed)
    }

    test("perspective is part of the information-set digest") {
        val env = environment()
        val firstPerspective = observation(env, perspectiveIndex = 0)
        val secondPerspective = observation(env, perspectiveIndex = 1)

        StateDigest.compute(firstPerspective) shouldNotBe StateDigest.compute(secondPerspective)
    }
})
