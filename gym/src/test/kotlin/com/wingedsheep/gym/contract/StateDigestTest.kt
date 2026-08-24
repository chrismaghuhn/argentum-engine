package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.FixedManaOutput
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
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
                    paymentDomain = PaymentDomainV4(
                        requiredCost = "{1}{B}",
                        costUnits = listOf(
                            PaymentCostUnitDomain(0, PaymentCostKind.GENERIC, 1),
                            PaymentCostUnitDomain(
                                symbolIndex = 1,
                                kind = PaymentCostKind.COLORED,
                                amount = 1,
                                allowedColors = setOf(PaymentManaColor.BLACK),
                            ),
                        ),
                        currentPool = PaymentPoolDomainV4(),
                        sourceActivations = emptyList(),
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
        val domain = PaymentDomainV4(
            requiredCost = "{B}",
            costUnits = listOf(
                PaymentCostUnitDomain(
                    symbolIndex = 0,
                    kind = PaymentCostKind.COLORED,
                    amount = 1,
                    allowedColors = setOf(PaymentManaColor.BLACK),
                ),
            ),
            currentPool = PaymentPoolDomainV4(),
            sourceActivations = listOf(
                PaymentSourceActivationDomain(
                    sourceId = com.wingedsheep.sdk.model.EntityId("bundle-source"),
                    sourceName = "Bundle Source",
                    manaAbilityKey = "bundle-ability",
                    productionChoices = listOf(
                        com.wingedsheep.engine.core.ProductionChoice(
                            producedColor = PaymentManaColor.BLACK,
                            fixedOutputs = listOf(
                                FixedManaOutput(0, PaymentManaColor.BLACK),
                                FixedManaOutput(1, PaymentManaColor.GREEN),
                            ),
                        ),
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
                    sourceActivations = listOf(
                        domain.sourceActivations.single().copy(
                            productionChoices = listOf(
                                com.wingedsheep.engine.core.ProductionChoice(
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
        val domain = PaymentDomainV4(
            requiredCost = "{G}",
            costUnits = listOf(
                PaymentCostUnitDomain(
                    symbolIndex = 0,
                    kind = PaymentCostKind.COLORED,
                    amount = 1,
                    allowedColors = setOf(PaymentManaColor.GREEN),
                ),
            ),
            currentPool = PaymentPoolDomainV4(
                green = 1,
                certifiedFloatingBuckets = listOf(
                    CertifiedFloatingManaBucketDomainV4(
                        sourceId = com.wingedsheep.sdk.model.EntityId("forest-source"),
                        poolColor = PaymentManaColor.GREEN,
                        sourceSubtypes = listOf("Cave", "Forest"),
                        amount = 1,
                    ),
                ),
            ),
            sourceActivations = emptyList(),
        )
        val first = base.copy(legalActions = base.legalActions + candidate.copy(paymentDomain = domain))
        val second = first.copy(
            legalActions = first.legalActions.map { action ->
                if (action.actionId != candidate.actionId) action else action.copy(
                    paymentDomain = domain.copy(
                        currentPool = domain.currentPool.copy(
                            certifiedFloatingBuckets = listOf(
                                CertifiedFloatingManaBucketDomainV4(
                                    sourceId = com.wingedsheep.sdk.model.EntityId("forest-source"),
                                    poolColor = PaymentManaColor.GREEN,
                                    sourceSubtypes = listOf("Forest"),
                                    amount = 1,
                                ),
                            ),
                        ),
                    ),
                )
            },
        )

        StateDigest.compute(first) shouldNotBe StateDigest.compute(second)

    }

    test("certified floating source bucket order is not digest-relevant") {
        val base = observation(environment())
        val candidate = LegalActionView(
            actionId = 9004,
            kind = "CastSpell",
            description = "Cast bucketed floating spell",
            affordable = true,
            manaCost = "{1}",
            requiresStructuredAction = true,
        )
        val domain = PaymentDomainV4(
            requiredCost = "{1}",
            costUnits = listOf(PaymentCostUnitDomain(0, PaymentCostKind.GENERIC, 1)),
            currentPool = PaymentPoolDomainV4(
                green = 2,
                certifiedFloatingBuckets = listOf(
                    CertifiedFloatingManaBucketDomainV4(
                        com.wingedsheep.sdk.model.EntityId("e108"),
                        PaymentManaColor.GREEN,
                        listOf("Forest"),
                        1,
                    ),
                    CertifiedFloatingManaBucketDomainV4(
                        com.wingedsheep.sdk.model.EntityId("e117"),
                        PaymentManaColor.GREEN,
                        listOf("Forest"),
                        1,
                    ),
                ),
            ),
            sourceActivations = emptyList(),
        )
        val first = base.copy(legalActions = base.legalActions + candidate.copy(paymentDomain = domain))
        val second = first.copy(
            legalActions = first.legalActions.map { action ->
                if (action.actionId != candidate.actionId) action else action.copy(
                    paymentDomain = domain.copy(
                        currentPool = domain.currentPool.copy(
                            certifiedFloatingBuckets = domain.currentPool.certifiedFloatingBuckets.reversed(),
                        ),
                    ),
                )
            },
        )

        StateDigest.compute(first) shouldBe StateDigest.compute(second)

        val changed = first.copy(
            legalActions = first.legalActions.map { action ->
                if (action.actionId != candidate.actionId) action else action.copy(
                    paymentDomain = domain.copy(
                        currentPool = domain.currentPool.copy(
                            certifiedFloatingBuckets = listOf(
                                CertifiedFloatingManaBucketDomainV4(
                                    com.wingedsheep.sdk.model.EntityId("different-source"),
                                    PaymentManaColor.GREEN,
                                    listOf("Forest"),
                                    1,
                                ),
                                CertifiedFloatingManaBucketDomainV4(
                                    com.wingedsheep.sdk.model.EntityId("e117"),
                                    PaymentManaColor.GREEN,
                                    listOf("Forest"),
                                    1,
                                ),
                            ),
                        ),
                    ),
                )
            },
        )
        StateDigest.compute(first) shouldNotBe StateDigest.compute(changed)
    }

    test("heterogeneous source-color provenance is digest-relevant and order-canonical") {
        val base = observation(environment())
        val candidate = LegalActionView(
            actionId = 9005,
            kind = "CastSpell",
            description = "Cast heterogeneous floating spell",
            affordable = true,
            manaCost = "{B}{G}",
            requiresStructuredAction = true,
        )
        val domain = PaymentDomainV4(
            requiredCost = "{B}{G}",
            costUnits = listOf(
                PaymentCostUnitDomain(
                    0,
                    PaymentCostKind.COLORED,
                    1,
                    setOf(PaymentManaColor.BLACK),
                ),
                PaymentCostUnitDomain(
                    1,
                    PaymentCostKind.COLORED,
                    1,
                    setOf(PaymentManaColor.GREEN),
                ),
            ),
            currentPool = PaymentPoolDomainV4(
                black = 1,
                green = 3,
                certifiedFloatingBuckets = listOf(
                    CertifiedFloatingManaBucketDomainV4(
                        com.wingedsheep.sdk.model.EntityId("e108"),
                        PaymentManaColor.BLACK,
                        emptyList(),
                        1,
                    ),
                    CertifiedFloatingManaBucketDomainV4(
                        com.wingedsheep.sdk.model.EntityId("e117"),
                        PaymentManaColor.GREEN,
                        listOf("Forest"),
                        1,
                    ),
                    CertifiedFloatingManaBucketDomainV4(
                        com.wingedsheep.sdk.model.EntityId("e136"),
                        PaymentManaColor.GREEN,
                        listOf("Cave"),
                        2,
                    ),
                ),
            ),
            sourceActivations = emptyList(),
        )
        val first = base.copy(legalActions = base.legalActions + candidate.copy(paymentDomain = domain))
        val reordered = first.copy(
            legalActions = first.legalActions.map { action ->
                if (action.actionId != candidate.actionId) action else action.copy(
                    paymentDomain = domain.copy(
                        currentPool = domain.currentPool.copy(
                            certifiedFloatingBuckets = domain.currentPool.certifiedFloatingBuckets.reversed(),
                        ),
                    ),
                )
            },
        )
        val changed = first.copy(
            legalActions = first.legalActions.map { action ->
                if (action.actionId != candidate.actionId) action else action.copy(
                    paymentDomain = domain.copy(
                        currentPool = domain.currentPool.copy(
                            certifiedFloatingBuckets = listOf(
                                domain.currentPool.certifiedFloatingBuckets[0].copy(
                                    poolColor = PaymentManaColor.GREEN,
                                ),
                                domain.currentPool.certifiedFloatingBuckets[1].copy(
                                    poolColor = PaymentManaColor.BLACK,
                                ),
                                domain.currentPool.certifiedFloatingBuckets[2],
                            ),
                        ),
                    ),
                )
            },
        )

        StateDigest.compute(first) shouldBe StateDigest.compute(reordered)
        StateDigest.compute(first) shouldNotBe StateDigest.compute(changed)
    }

    test("perspective is part of the information-set digest") {
        val env = environment()
        val firstPerspective = observation(env, perspectiveIndex = 0)
        val secondPerspective = observation(env, perspectiveIndex = 1)

        StateDigest.compute(firstPerspective) shouldNotBe StateDigest.compute(secondPerspective)
    }
})
