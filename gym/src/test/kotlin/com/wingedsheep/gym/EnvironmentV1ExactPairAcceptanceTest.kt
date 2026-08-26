package com.wingedsheep.gym

import com.wingedsheep.engine.core.BudgetModalResponse
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DamageEdgeAmount
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.DiagnosticKind
import com.wingedsheep.engine.core.DistributionResponse
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.engine.core.PilesSplitResponse
import com.wingedsheep.engine.core.ReplacementChosenResponse
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.core.UnsupportedPathFailure
import com.wingedsheep.engine.registry.CardDefinitionMissingException
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.contract.ObservationResult
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.ObservationCanonicalizer
import com.wingedsheep.gym.contract.AttackBandConstraintsV1
import com.wingedsheep.gym.contract.AttackCoAttackerRequirementV1
import com.wingedsheep.gym.contract.AttackDeclarationDomainV1
import com.wingedsheep.gym.contract.ActionTargetDomainV1
import com.wingedsheep.gym.contract.PendingDecisionKind
import com.wingedsheep.gym.contract.PaymentCostKind
import com.wingedsheep.gym.contract.PaymentCostUnitDomain
import com.wingedsheep.gym.contract.CertifiedFloatingManaBucketDomainV4
import com.wingedsheep.gym.contract.PaymentDomainV4
import com.wingedsheep.gym.contract.PaymentPoolDomainV4
import com.wingedsheep.gym.contract.PaymentSourceActivationDomain
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.EntityFeatures
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.contract.StateDigest
import com.wingedsheep.gym.contract.TargetRequirementDomain
import com.wingedsheep.gym.contract.ZoneView
import com.wingedsheep.gym.contract.ResolvedAction
import com.wingedsheep.gym.contract.*
import com.wingedsheep.gym.service.DeckResolver
import com.wingedsheep.gym.service.DeckSpec
import com.wingedsheep.gym.service.EnvConfig
import com.wingedsheep.gym.service.EnvId
import com.wingedsheep.gym.service.MultiEnvService
import com.wingedsheep.gym.service.PlayerSpec
import com.wingedsheep.gym.service.StepRequest
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import com.wingedsheep.sdk.serialization.CardSerialization
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.nio.file.Files
import java.net.URLClassLoader
import java.security.MessageDigest
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.readBytes
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.minutes

/**
 * Durable exact-pair Environment V1 setup and corpus gate.
 *
 * This file is intentionally test-only. It drives the trusted public service surface and never
 * passes the environment or an internal registry to [DeterministicExternalPolicy].
 */
class EnvironmentV1ExactPairAcceptanceTest : FunSpec({

    test("the locked Akiri and Chevill files resolve exactly 146 unique cards") {
        val akiri = readLockedDeck("akiri-v0.1.txt")
        val chevill = readLockedDeck("chevill-v0.1.txt")
        val uniqueCards = (akiri.cards + chevill.cards).distinct()
        val registry = exactPairRegistry()

        sha256(lockedDeckPath("akiri-v0.1.txt")) shouldBe AKIRI_SHA256
        sha256(lockedDeckPath("chevill-v0.1.txt")) shouldBe CHEVILL_SHA256
        akiri.cards.size shouldBe 100
        chevill.cards.size shouldBe 100
        uniqueCards.size shouldBe 146
        akiri.commander shouldBe "Akiri, Fearless Voyager"
        chevill.commander shouldBe "Chevill, Bane of Monsters"
        uniqueCards.filterNot(registry::hasCard).shouldBeEmpty()
    }

    test("the external policy has no engine-state or diagnostic dependency") {
        val source = repositoryRoot()
            .resolve("gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExternalPolicy.kt")
            .readText()
        listOf(
            "GameState",
            "CardRegistry",
            "ActionRegistry",
            "EpisodeDiagnostics",
            "GameEnvironment",
            "ManaSolver",
            "AutomaticPaymentSelection",
            "AutoPay",
            "autoPaySuggestion",
            "autoTapSuggestion",
        ).forEach { forbidden ->
            check(forbidden !in source) {
                "Observation-only acceptance policy contains forbidden symbol: $forbidden"
            }
        }
        listOf("validAttackers", "validAttackTargets").forEach { legacyHint ->
            check(legacyHint !in source) {
                "DeclareAttackers policy must not consume legacy flat hint: $legacyHint"
            }
        }
    }

    test("the external policy turns the public PaymentDomainV4 into ExplicitV2 PaymentPlanV2") {
        val player = EntityId("player-0")
        val blackSource = EntityId("source-black")
        val anySource = EntityId("source-any")
        val paymentDomain = PaymentDomainV4(
            requiredCost = "{1}{B}",
            costUnits = listOf(
                PaymentCostUnitDomain(0, PaymentCostKind.GENERIC, amount = 1),
                PaymentCostUnitDomain(
                    symbolIndex = 1,
                    kind = PaymentCostKind.COLORED,
                    amount = 1,
                    allowedColors = setOf(PaymentManaColor.BLACK),
                ),
            ),
            currentPool = PaymentPoolDomainV4(),
            sourceActivations = listOf(
                PaymentSourceActivationDomain(
                    sourceId = blackSource,
                    sourceName = "Black Source",
                    manaAbilityKey = "black-ability",
                    productionChoices = listOf(ProductionChoice(PaymentManaColor.BLACK)),
                ),
                PaymentSourceActivationDomain(
                    sourceId = anySource,
                    sourceName = "Any Source",
                    manaAbilityKey = "any-ability",
                    productionChoices = listOf(
                        ProductionChoice(PaymentManaColor.BLACK),
                        ProductionChoice(PaymentManaColor.GREEN),
                    ),
                ),
            ),
        )
        val action = LegalActionView(
            actionId = 7,
            kind = "ActivateAbility",
            description = "Activate public payment-domain source",
            affordable = true,
            manaCost = "{1}{B}",
            paymentDomain = paymentDomain,
            requiresStructuredAction = true,
            requiredPayloadFields = listOf("paymentStrategy"),
            actionSemantics = buildJsonObject {
                put("type", "ActivateAbility")
                put("abilityKey", "ability-1")
            },
        )
        val observation = TrainingObservation(
            schemaHash = "test-schema",
            perspectivePlayerId = player,
            agentToAct = player,
            turnNumber = 1,
            phase = com.wingedsheep.sdk.core.Phase.PRECOMBAT_MAIN,
            step = com.wingedsheep.sdk.core.Step.PRECOMBAT_MAIN,
            activePlayerId = player,
            priorityPlayerId = player,
            players = emptyList(),
            zones = emptyList(),
            stack = emptyList(),
            pendingDecision = null,
            legalActions = listOf(action),
            terminated = false,
            truncated = false,
            winnerId = null,
            stateDigest = "digest",
        )

        val choice = DeterministicExternalPolicy().choose(
            observation,
            DeterministicPolicyState(policySeed = 1L),
        )
        check(choice is SemanticChoice.Action) { "Expected an action choice, got $choice" }
        val payload = choice.payload ?: error("Payment action did not publish a payload")
        val strategy = Json {
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }.decodeFromJsonElement(
            PaymentStrategy.serializer(),
            payload["paymentStrategy"] ?: error("Payment payload omitted paymentStrategy"),
        )
        check(strategy is PaymentStrategy.ExplicitV2) { "Expected ExplicitV2 payment strategy: $strategy" }
        check(strategy.manaAbilitiesToActivate.isEmpty()) {
            "Payment policy must not emit legacy source handles"
        }
        val plan = strategy.paymentPlan ?: error("Payment policy omitted PaymentPlanV2")
        plan.sourceActivations.map { it.sourceId }.toSet() shouldBe setOf(blackSource, anySource)
        plan.spendAllocation.costUnits.map { it.symbolIndex } shouldBe listOf(0, 1)
        plan.spendAllocation.costUnits.sumOf { it.spends.sumOf { spend -> spend.amount } } shouldBe 2

        val heterogeneousDomain = paymentDomain.copy(
            currentPool = PaymentPoolDomainV4(
                black = 1,
                green = 3,
                certifiedFloatingBuckets = listOf(
                        CertifiedFloatingManaBucketDomainV4(
                            sourceId = EntityId("floating-black"),
                            poolColor = PaymentManaColor.BLACK,
                            sourceSubtypes = listOf("Forest"),
                            amount = 1,
                        ),
                        CertifiedFloatingManaBucketDomainV4(
                            sourceId = EntityId("floating-green"),
                            poolColor = PaymentManaColor.GREEN,
                            sourceSubtypes = listOf("Forest"),
                            amount = 3,
                        ),
                ),
            ),
            sourceActivations = emptyList(),
        )
        val heterogeneousChoice = DeterministicExternalPolicy().choose(
            observation.copy(
                legalActions = listOf(action.copy(paymentDomain = heterogeneousDomain)),
            ),
            DeterministicPolicyState(policySeed = 2L),
        )
        check(heterogeneousChoice is SemanticChoice.Action) {
            "Expected a heterogeneous-domain action choice, got $heterogeneousChoice"
        }
        val heterogeneousPayload = heterogeneousChoice.payload
            ?: error("Heterogeneous payment action did not publish a payload")
        val heterogeneousStrategy = Json {
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }.decodeFromJsonElement(
            PaymentStrategy.serializer(),
            heterogeneousPayload["paymentStrategy"]
                ?: error("Heterogeneous payment payload omitted paymentStrategy"),
        )
        check(heterogeneousStrategy is PaymentStrategy.ExplicitV2) {
            "Expected ExplicitV2 heterogeneous payment strategy: $heterogeneousStrategy"
        }
        val heterogeneousPlan = heterogeneousStrategy.paymentPlan
            ?: error("Heterogeneous payment policy omitted PaymentPlanV2")
        heterogeneousPlan.spendAllocation.costUnits
            .flatMap { it.spends }
            .map { spend ->
                Triple(spend.floatingSourceId, spend.poolColor, spend.floatingSourceSubtypes)
            }
            .toSet() shouldBe setOf(
                Triple(EntityId("floating-black"), PaymentManaColor.BLACK, listOf("Forest")),
                Triple(EntityId("floating-green"), PaymentManaColor.GREEN, listOf("Forest")),
            )
    }

    test("the external policy completes CommanderIdentity manaColorChoice from public data") {
        val player = EntityId("player-0")
        val commander = EntityFeatures(
            entityId = EntityId("commander-0"),
            cardDefinitionId = "public-commander",
            name = "Public Commander",
            zone = Zone.COMMAND,
            ownerId = player,
            controllerId = null,
            types = setOf("CREATURE"),
            subtypes = emptySet(),
            colors = setOf("RED", "WHITE"),
            keywords = emptySet(),
            manaCost = "",
            manaValue = 0,
            power = null,
            toughness = null,
        )
        val action = LegalActionView(
            actionId = 42,
            kind = "ActivateAbility",
            description = "Add one mana from the commander's color identity",
            affordable = true,
            isManaAbility = true,
            requiresStructuredAction = true,
            requiredPayloadFields = listOf("manaColorChoice"),
            actionSemantics = buildJsonObject {
                put("type", "ActivateAbility")
                put("playerId", player.value)
                put("abilityKey", buildJsonObject {
                    put("ability", buildJsonObject {
                        put("effect", buildJsonObject {
                            put("colorSet", buildJsonObject {
                                put("type", "ManaColorSet.CommanderIdentity")
                            })
                        })
                    })
                })
            },
        )
        val observation = TrainingObservation(
            schemaHash = "test-schema",
            perspectivePlayerId = player,
            agentToAct = player,
            turnNumber = 1,
            phase = com.wingedsheep.sdk.core.Phase.PRECOMBAT_MAIN,
            step = com.wingedsheep.sdk.core.Step.PRECOMBAT_MAIN,
            activePlayerId = player,
            priorityPlayerId = player,
            players = emptyList(),
            zones = listOf(
                ZoneView(
                    ownerId = player,
                    zoneType = Zone.COMMAND,
                    hidden = false,
                    size = 1,
                    cards = listOf(commander),
                )
            ),
            stack = emptyList(),
            pendingDecision = null,
            legalActions = listOf(action),
            terminated = false,
            truncated = false,
            winnerId = null,
            stateDigest = "digest",
        )

        val choice = DeterministicExternalPolicy().choose(
            observation,
            DeterministicPolicyState(policySeed = 1L),
        )
        check(choice is SemanticChoice.Action) { "Expected a public mana-color action, got $choice" }
        choice.payload?.get("manaColorChoice") shouldBe JsonPrimitive("WHITE")
    }

    test("the external policy maps source-bound costPayment from public cost semantics") {
        val player = EntityId("player-0")
        val source = EntityId("source-permanent")
        val sourceBoundCost = buildJsonObject {
            put("type", "CostComposite")
            put(
                "costs",
                JsonArray(
                    listOf(
                        buildJsonObject { put("type", "CostTap") },
                        buildJsonObject { put("type", "CostSacrificeSelf") },
                    ),
                ),
            )
        }
        val action = LegalActionView(
            actionId = 101,
            kind = "ActivateAbility",
            description = "opaque source-bound activation",
            affordable = true,
            sourceEntityId = source,
            requiresStructuredAction = true,
            requiredPayloadFields = listOf("costPayment"),
            actionSemantics = buildJsonObject {
                put("type", "ActivateAbility")
                put("abilityKey", buildJsonObject {
                    put("ability", buildJsonObject {
                        put("cost", sourceBoundCost)
                    })
                })
            },
        )
        val observation = TrainingObservation(
            schemaHash = "test-schema",
            perspectivePlayerId = player,
            agentToAct = player,
            turnNumber = 1,
            phase = com.wingedsheep.sdk.core.Phase.PRECOMBAT_MAIN,
            step = com.wingedsheep.sdk.core.Step.PRECOMBAT_MAIN,
            activePlayerId = player,
            priorityPlayerId = player,
            players = emptyList(),
            zones = emptyList(),
            stack = emptyList(),
            pendingDecision = null,
            legalActions = listOf(action),
            terminated = false,
            truncated = false,
            winnerId = null,
            stateDigest = "digest",
        )

        val choice = DeterministicExternalPolicy().choose(
            observation,
            DeterministicPolicyState(policySeed = 1L),
        )
        check(choice is SemanticChoice.Action) {
            "Expected a source-bound cost-payment action, got $choice"
        }
        val payload = choice.payload ?: error("Cost-payment action did not publish a payload")
        check("additionalCostPayment" !in payload) {
            "costPayment must not be emitted through the additional-cost field"
        }
        val payment = Json {
            ignoreUnknownKeys = true
        }.decodeFromJsonElement(
            AdditionalCostPayment.serializer(),
            payload["costPayment"] ?: error("Cost-payment action omitted costPayment"),
        )
        payment.sacrificedPermanents shouldBe listOf(source)
        payment.tappedPermanents shouldBe listOf(source)
        payment.discardedCards.shouldBeEmpty()
        payment.exiledCards.shouldBeEmpty()
        payment.variableCostPermanents.shouldBeEmpty()
        payment.beheldCards.shouldBeEmpty()
        payment.bouncedPermanents.shouldBeEmpty()
        payment.blightTargets.shouldBeEmpty()
        payment.distributedCounterRemovals.shouldBeEmpty()
        payment.lifePaid shouldBe 0
        payment.blightAmount shouldBe 0
        payment.payXLifeAmount shouldBe 0
    }

    test("COSTPAY-01 maps TapSelf costPayment to the public source") {
        val source = EntityId("source-tap")
        val action = sourceBoundCostPaymentAction(
            actionId = 103,
            source = source,
            cost = buildJsonObject { put("type", "CostTap") },
        )

        val payment = decodeCostPayment(
            DeterministicExternalPolicy().choose(
                publicActionObservation(action),
                DeterministicPolicyState(policySeed = 1L),
            ),
        )

        payment.tappedPermanents shouldBe listOf(source)
        payment.sacrificedPermanents.shouldBeEmpty()
        payment.discardedCards.shouldBeEmpty()
        payment.exiledCards.shouldBeEmpty()
        payment.variableCostPermanents.shouldBeEmpty()
    }

    test("COSTPAY-02 maps SacrificeSelf costPayment to the public source") {
        val source = EntityId("source-sacrifice")
        val action = sourceBoundCostPaymentAction(
            actionId = 104,
            source = source,
            cost = buildJsonObject { put("type", "CostSacrificeSelf") },
        )

        val payment = decodeCostPayment(
            DeterministicExternalPolicy().choose(
                publicActionObservation(action),
                DeterministicPolicyState(policySeed = 1L),
            ),
        )

        payment.tappedPermanents.shouldBeEmpty()
        payment.sacrificedPermanents shouldBe listOf(source)
        payment.discardedCards.shouldBeEmpty()
        payment.exiledCards.shouldBeEmpty()
        payment.variableCostPermanents.shouldBeEmpty()
    }

    test("COSTPAY-03 maps choice-bearing costPayment from its published sacrifice domain") {
        val source = EntityId("source-choice")
        val firstTarget = EntityId("sacrifice-choice-a")
        val secondTarget = EntityId("sacrifice-choice-b")
        val action = choiceBearingCostPaymentAction(
            actionId = 105,
            source = source,
            targets = listOf(secondTarget, firstTarget),
            count = 1,
            min = 1,
            max = 1,
        )

        val payment = decodeCostPayment(
            DeterministicExternalPolicy().choose(
                publicActionObservation(action),
                DeterministicPolicyState(policySeed = 1L),
            ),
        )

        payment.sacrificedPermanents shouldBe listOf(firstTarget)
        payment.tappedPermanents.shouldBeEmpty()
    }

    test("COSTPAY-04 keeps costPayment fail-closed without a published sacrifice domain") {
        val action = choiceBearingCostPaymentAction(
            actionId = 106,
            source = EntityId("source-without-domain"),
            targets = emptyList(),
            count = 1,
            min = 1,
            max = 1,
        )

        val choice = DeterministicExternalPolicy().choose(
            publicActionObservation(action),
            DeterministicPolicyState(policySeed = 1L),
        )

        check(choice is SemanticChoice.Gap) { "Expected A5 gap, got $choice" }
        choice.code shouldBe "A5_DECISION_GAP"
    }

    test("COSTPAY-05 keeps costPayment fail-closed when cardinality exceeds the domain") {
        val action = choiceBearingCostPaymentAction(
            actionId = 107,
            source = EntityId("source-insufficient-domain"),
            targets = listOf(EntityId("only-sacrifice-target")),
            count = 2,
            min = 2,
            max = 2,
        )

        val choice = DeterministicExternalPolicy().choose(
            publicActionObservation(action),
            DeterministicPolicyState(policySeed = 1L),
        )

        check(choice is SemanticChoice.Gap) { "Expected A5 gap, got $choice" }
        choice.code shouldBe "A5_DECISION_GAP"
    }

    test("COSTPAY-06 does not reinterpret a choice-bearing sacrifice as SacrificeSelf") {
        val source = EntityId("source-choice")
        val otherTarget = EntityId("other-sacrifice-target")
        val action = choiceBearingCostPaymentAction(
            actionId = 108,
            source = source,
            targets = listOf(source, otherTarget),
            count = 1,
            min = 1,
            max = 1,
        )

        val payment = decodeCostPayment(
            DeterministicExternalPolicy().choose(
                publicActionObservation(action),
                DeterministicPolicyState(policySeed = 1L),
            ),
        )

        payment.sacrificedPermanents shouldBe listOf(otherTarget)
        payment.tappedPermanents.shouldBeEmpty()
    }

    test("TARGETPOL-01 uses the public Zone wire name for a card target") {
        val player = EntityId("player-0")
        val card = EntityFeatures(
            entityId = EntityId("card-in-graveyard"),
            cardDefinitionId = "public-card",
            name = "Public Card",
            zone = Zone.GRAVEYARD,
            ownerId = player,
            controllerId = null,
            types = emptySet(),
            subtypes = emptySet(),
            colors = emptySet(),
            keywords = emptySet(),
            manaCost = "",
            manaValue = 0,
            power = null,
            toughness = null,
        )
        val action = LegalActionView(
            actionId = 109,
            kind = "CastSpell",
            description = "public card target",
            affordable = true,
            targetEntityIds = listOf(card.entityId),
            minTargets = 1,
            maxTargets = 1,
            requiresStructuredAction = true,
            requiredPayloadFields = listOf("targets"),
            actionSemantics = buildJsonObject { put("type", "CastSpell") },
        )

        val choice = DeterministicExternalPolicy().choose(
            publicActionObservation(action, player).copy(
                zones = listOf(
                    ZoneView(
                        ownerId = player,
                        zoneType = Zone.GRAVEYARD,
                        hidden = false,
                        size = 1,
                        cards = listOf(card),
                    ),
                ),
            ),
            DeterministicPolicyState(policySeed = 1L),
        )
        check(choice is SemanticChoice.Action) { "Expected a target action, got $choice" }
        val target = checkNotNull(choice.payload?.get("targets"))
            .jsonArray
            .single()
            .jsonObject

        target["zone"] shouldBe JsonPrimitive("Graveyard")
    }

    test("TARGETPOL-02 selects every fixed target slot from the public target domain") {
        val player = EntityId("player-0")
        val firstTarget = EntityId("target-slot-a")
        val secondTarget = EntityId("target-slot-b")
        fun feature(id: EntityId) = EntityFeatures(
            entityId = id,
            cardDefinitionId = "public-permanent",
            name = "Public Permanent",
            zone = Zone.BATTLEFIELD,
            ownerId = player,
            controllerId = player,
            types = setOf("CREATURE"),
            subtypes = emptySet(),
            colors = emptySet(),
            keywords = emptySet(),
            manaCost = "",
            manaValue = 0,
            power = 1,
            toughness = 1,
        )
        val action = LegalActionView(
            actionId = 110,
            kind = "CastSpell",
            description = "public fixed multi-target action",
            affordable = true,
            targetEntityIds = emptyList(),
            targetDomain = ActionTargetDomainV1(
                requirements = listOf(
                    publicTargetRequirement(0, listOf(firstTarget)),
                    publicTargetRequirement(1, listOf(secondTarget)),
                ),
            ),
            requiresStructuredAction = true,
            requiredPayloadFields = listOf("targets"),
            actionSemantics = buildJsonObject { put("type", "CastSpell") },
        )

        val choice = DeterministicExternalPolicy().choose(
            publicActionObservation(action, player).copy(
                zones = listOf(
                    ZoneView(
                        ownerId = player,
                        zoneType = Zone.BATTLEFIELD,
                        hidden = false,
                        size = 2,
                        cards = listOf(feature(firstTarget), feature(secondTarget)),
                    ),
                ),
            ),
            DeterministicPolicyState(policySeed = 1L),
        )
        check(choice is SemanticChoice.Action) { "Expected a target action, got $choice" }
        val selectedIds = checkNotNull(choice.payload?.get("targets"))
            .jsonArray
            .map { target -> target.jsonObject.getValue("entityId").jsonPrimitive.content }

        selectedIds shouldBe listOf(firstTarget.value, secondTarget.value)
    }

    test("the external policy completes repeatCount from public action semantics") {
        val action = LegalActionView(
            actionId = 111,
            kind = "ActivateAbility",
            description = "public repeatable activation",
            affordable = true,
            requiresStructuredAction = true,
            requiredPayloadFields = listOf("repeatCount"),
            actionSemantics = buildJsonObject {
                put("type", "ActivateAbility")
                put("repeatCount", 1)
            },
        )

        val choice = DeterministicExternalPolicy().choose(
            publicActionObservation(action),
            DeterministicPolicyState(policySeed = 1L),
        )

        val payload = policyPayload(choice)
        payload["repeatCount"] shouldBe JsonPrimitive(1)
    }

    test("the external policy fails closed for an unknown required payload field") {
        val action = LegalActionView(
            actionId = 112,
            kind = "ActivateAbility",
            description = "public action with an unknown field",
            affordable = true,
            validSacrificeTargets = emptyList(),
            sacrificeCount = 0,
            sacrificeMinCount = 0,
            sacrificeMaxCount = 0,
            requiresStructuredAction = true,
            requiredPayloadFields = listOf("additionalCostPayment", "futurePayload"),
            actionSemantics = buildJsonObject {
                put("type", "ActivateAbility")
                put("futurePayload", 1)
            },
        )

        val choice = DeterministicExternalPolicy().choose(
            publicActionObservation(action),
            DeterministicPolicyState(policySeed = 1L),
        )

        val gap = choice as? SemanticChoice.Gap
            ?: error("Unknown required payload field was not rejected: $choice")
        gap.code shouldBe "A5_DECISION_GAP"
    }

    test("ATTACKPOL-01 chooses an explicit zero-attacker declaration") {
        val action = attackPolicyAction(
            domain = attackPolicyDomain(
                attackerToDefenders = emptyMap(),
                canDeclareZeroAttackers = true,
            ),
        )

        val choice = DeterministicExternalPolicy().choose(
            publicActionObservation(action),
            DeterministicPolicyState(policySeed = 1L),
        )
        val payload = policyPayload(choice)

        payload["attackers"] shouldBe JsonObject(emptyMap())
        payload["bands"] shouldBe JsonArray(emptyList())
    }

    test("ATTACKPOL-02 includes every mandatory attacker") {
        val attacker = EntityId("attacker-mandatory")
        val defender = EntityId("defender-mandatory")
        val action = attackPolicyAction(
            domain = attackPolicyDomain(
                attackerToDefenders = mapOf(attacker to listOf(defender)),
                mandatoryAttackers = listOf(attacker),
                canDeclareZeroAttackers = false,
                maxAttackers = 1,
            ),
        )

        val payload = policyPayload(
            DeterministicExternalPolicy().choose(
                publicActionObservation(action),
                DeterministicPolicyState(policySeed = 1L),
            ),
        )

        payload["attackers"] shouldBe JsonObject(
            mapOf(attacker.value to JsonPrimitive(defender.value)),
        )
    }

    test("ATTACKPOL-03 satisfies every selected co-attacker requirement") {
        val required = EntityId("attacker-required")
        val coAttacker = EntityId("attacker-co")
        val requiredDefender = EntityId("defender-required")
        val coAttackerDefender = EntityId("defender-co")
        val action = attackPolicyAction(
            domain = attackPolicyDomain(
                attackerToDefenders = mapOf(
                    required to listOf(requiredDefender),
                    coAttacker to listOf(coAttackerDefender),
                ),
                mandatoryAttackers = listOf(required),
                canDeclareZeroAttackers = false,
                maxAttackers = 2,
                coAttackerRequirements = mapOf(
                    required to listOf(
                        AttackCoAttackerRequirementV1(anyOf = listOf(coAttacker)),
                    ),
                ),
            ),
        )

        val payload = policyPayload(
            DeterministicExternalPolicy().choose(
                publicActionObservation(action),
                DeterministicPolicyState(policySeed = 1L),
            ),
        )
        val attackers = payload.getValue("attackers").jsonObject.keys

        attackers shouldBe setOf(required.value, coAttacker.value)
    }

    test("ATTACKPOL-04 never exceeds the public maximum attacker count") {
        val attackerA = EntityId("attacker-a")
        val attackerB = EntityId("attacker-b")
        val attackerC = EntityId("attacker-c")
        val defender = EntityId("defender")
        val action = attackPolicyAction(
            domain = attackPolicyDomain(
                attackerToDefenders = mapOf(
                    attackerA to listOf(defender),
                    attackerB to listOf(defender),
                    attackerC to listOf(defender),
                ),
                canDeclareZeroAttackers = false,
                maxAttackers = 1,
            ),
        )

        val payload = policyPayload(
            DeterministicExternalPolicy().choose(
                publicActionObservation(action),
                DeterministicPolicyState(policySeed = 1L),
            ),
        )

        payload.getValue("attackers").jsonObject.size shouldBe 1
    }

    test("ATTACKPOL-05 chooses each defender only from that attacker's relation") {
        val attackerA = EntityId("attacker-a")
        val attackerB = EntityId("attacker-b")
        val defenderA = EntityId("defender-a")
        val defenderB = EntityId("defender-b")
        val wrongForB = EntityId("defender-z")
        val action = attackPolicyAction(
            domain = attackPolicyDomain(
                attackerToDefenders = mapOf(
                    attackerA to listOf(wrongForB, defenderA),
                    attackerB to listOf(wrongForB, defenderB),
                ),
                mandatoryAttackers = listOf(attackerA, attackerB),
                canDeclareZeroAttackers = false,
                maxAttackers = 2,
            ),
        )

        val payload = policyPayload(
            DeterministicExternalPolicy().choose(
                publicActionObservation(action),
                DeterministicPolicyState(policySeed = 1L),
            ),
        )

        payload["attackers"] shouldBe JsonObject(
            mapOf(
                attackerA.value to JsonPrimitive(defenderA.value),
                attackerB.value to JsonPrimitive(defenderB.value),
            ),
        )
    }

    test("ATTACKPOL-06 fails closed when the public attack domain is missing") {
        val choice = DeterministicExternalPolicy().choose(
            publicActionObservation(attackPolicyAction(domain = null)),
            DeterministicPolicyState(policySeed = 1L),
        )

        check(choice is SemanticChoice.Gap) { "Expected an attack-domain gap, got $choice" }
        choice.code shouldBe "A5_DECISION_GAP"
    }

    test("ATTACKPOL-07 fails closed when a required attacker has no defender") {
        val attacker = EntityId("attacker-without-defender")
        val choice = DeterministicExternalPolicy().choose(
            publicActionObservation(
                attackPolicyAction(
                    domain = attackPolicyDomain(
                        attackerToDefenders = mapOf(attacker to emptyList()),
                        mandatoryAttackers = listOf(attacker),
                        canDeclareZeroAttackers = false,
                        maxAttackers = 1,
                    ),
                ),
            ),
            DeterministicPolicyState(policySeed = 1L),
        )

        check(choice is SemanticChoice.Gap) { "Expected an attack-domain gap, got $choice" }
        choice.code shouldBe "A5_DECISION_GAP"
    }

    test("ATTACKPOL-08 ignores misleading flat target hints") {
        val attacker = EntityId("attacker-public-domain")
        val domainDefender = EntityId("defender-from-domain")
        val legacyDefender = EntityId("legacy-flat-defender")
        val action = attackPolicyAction(
            domain = attackPolicyDomain(
                attackerToDefenders = mapOf(attacker to listOf(domainDefender)),
                mandatoryAttackers = listOf(attacker),
                canDeclareZeroAttackers = false,
                maxAttackers = 1,
            ),
            targetEntityIds = listOf(legacyDefender),
        )

        val payload = policyPayload(
            DeterministicExternalPolicy().choose(
                publicActionObservation(action),
                DeterministicPolicyState(policySeed = 1L),
            ),
        )

        payload["attackers"] shouldBe JsonObject(
            mapOf(attacker.value to JsonPrimitive(domainDefender.value)),
        )
    }

    test("ATTACKPOL-09 always includes explicit empty bands") {
        val attacker = EntityId("attacker-bands")
        val action = attackPolicyAction(
            domain = attackPolicyDomain(
                attackerToDefenders = mapOf(attacker to listOf(EntityId("defender-bands"))),
                mandatoryAttackers = listOf(attacker),
                canDeclareZeroAttackers = false,
                maxAttackers = 1,
            ),
        )

        val payload = policyPayload(
            DeterministicExternalPolicy().choose(
                publicActionObservation(action),
                DeterministicPolicyState(policySeed = 1L),
            ),
        )

        payload.containsKey("bands") shouldBe true
        payload["bands"] shouldBe JsonArray(emptyList())
    }

    test("the external policy retains the published additionalCostPayment domain") {
        val player = EntityId("player-0")
        val source = EntityId("source-permanent")
        val firstTarget = EntityId("sacrifice-target-a")
        val secondTarget = EntityId("sacrifice-target-b")
        val action = LegalActionView(
            actionId = 102,
            kind = "CastSpell",
            description = "opaque additional-cost action",
            affordable = true,
            sourceEntityId = source,
            validSacrificeTargets = listOf(secondTarget, firstTarget),
            sacrificeCount = 2,
            sacrificeMinCount = 2,
            sacrificeMaxCount = 2,
            requiresStructuredAction = true,
            requiredPayloadFields = listOf("additionalCostPayment"),
            actionSemantics = buildJsonObject {
                put("type", "CastSpell")
            },
        )
        val observation = TrainingObservation(
            schemaHash = "test-schema",
            perspectivePlayerId = player,
            agentToAct = player,
            turnNumber = 1,
            phase = com.wingedsheep.sdk.core.Phase.PRECOMBAT_MAIN,
            step = com.wingedsheep.sdk.core.Step.PRECOMBAT_MAIN,
            activePlayerId = player,
            priorityPlayerId = player,
            players = emptyList(),
            zones = emptyList(),
            stack = emptyList(),
            pendingDecision = null,
            legalActions = listOf(action),
            terminated = false,
            truncated = false,
            winnerId = null,
            stateDigest = "digest",
        )

        val choice = DeterministicExternalPolicy().choose(
            observation,
            DeterministicPolicyState(policySeed = 1L),
        )
        check(choice is SemanticChoice.Action) {
            "Expected an additional-cost action, got $choice"
        }
        val payment = Json {
            ignoreUnknownKeys = true
        }.decodeFromJsonElement(
            AdditionalCostPayment.serializer(),
            choice.payload?.get("additionalCostPayment")
                ?: error("Additional-cost action omitted additionalCostPayment"),
        )
        payment.sacrificedPermanents shouldBe listOf(firstTarget, secondTarget)
        payment.tappedPermanents.shouldBeEmpty()
    }

    test("the real Viscera Seer action publishes its complete public sacrifice domain") {
        val service = MultiEnvService(exactPairRegistry())
        val episode = EpisodeConfig(
            seed = 4L,
            startingPlayerIndex = 0,
            seat0 = "Akiri",
            seat1 = "Chevill",
            rosterLabel = "Akiri-vs-Chevill",
        )
        val policy = DeterministicExternalPolicy()
        var policyState = DeterministicPolicyState(policySeed(episode))

        fun game(result: ObservationResult): TrainingObservation {
            check(result.diagnostics.isEmpty()) {
                "Viscera Seer characterization observed diagnostics: ${result.diagnostics}"
            }
            return result.observation as? TrainingObservation
                ?: error("Viscera Seer characterization requires TrainingObservation")
        }

        fun hasType(element: JsonElement?, expected: String): Boolean = when (element) {
            is JsonObject ->
                (element["type"] as? JsonPrimitive)?.content == expected ||
                    element.values.any { child -> hasType(child, expected) }
            is JsonArray -> element.any { child -> hasType(child, expected) }
            else -> false
        }

        try {
            val created = service.create(episode.envConfig())
            var observation = game(created.observation)
            repeat(64) { step ->
                val choice = policy.choose(observation, policyState)
                policyState = policyState.afterChoice()
                observation = when (choice) {
                    is SemanticChoice.Action -> game(
                        service.step(
                            StepRequest(
                                envId = created.envId,
                                actionId = choice.actionId,
                                action = choice.payload,
                            ),
                        ),
                    )
                    is SemanticChoice.Structured -> {
                        val pending = observation.pendingDecision
                            ?: error("Structured choice without a pending decision at step $step")
                        val decisionId = pending.decisionId
                            ?: error("Structured characterization decision has no decisionId")
                        game(
                            service.submitDecision(
                                envId = created.envId,
                                response = toDecisionResponse(decisionId, choice.selection),
                                actorId = observation.agentToAct,
                            ),
                        )
                    }
                    is SemanticChoice.Gap -> error(
                        "Unexpected policy gap before Viscera Seer characterization at step " +
                            "$step: $choice",
                    )
                }
            }

            val sacrificeActions = observation.legalActions.filter { action ->
                action.kind == "ActivateAbility" &&
                    action.requiredPayloadFields.contains("costPayment") &&
                    hasType(action.actionSemantics, "AtomSacrifice")
            }
            sacrificeActions.size shouldBe 1
            val visceraAction = sacrificeActions.single()
            visceraAction.kind shouldBe "ActivateAbility"
            visceraAction.requiredPayloadFields shouldBe listOf("costPayment")
            visceraAction.sourceEntityId shouldBe EntityId("e146")
            check(hasType(visceraAction.actionSemantics, "CostAtomWrapper")) {
                "Viscera Seer action did not publish CostAtomWrapper semantics"
            }
            check(hasType(visceraAction.actionSemantics, "AtomSacrifice")) {
                "Viscera Seer action did not publish AtomSacrifice semantics"
            }
            println(
                "VISCERA_SEER_PUBLIC_BATTLEFIELD " +
                    observation.zones
                        .filter { zone -> zone.zoneType == Zone.BATTLEFIELD && !zone.hidden }
                        .flatMap { zone -> zone.cards }
                        .joinToString { card ->
                            "${card.entityId}:${card.name}:${card.types.sorted()}"
                        },
            )
            println(
                "VISCERA_SEER_PUBLIC_DOMAIN_RAW " +
                    "targets=${visceraAction.validSacrificeTargets} " +
                    "count=${visceraAction.sacrificeCount} " +
                    "min=${visceraAction.sacrificeMinCount} " +
                    "max=${visceraAction.sacrificeMaxCount}",
            )
            visceraAction.sacrificeCount shouldBe 1
            visceraAction.sacrificeMinCount shouldBe 1
            visceraAction.sacrificeMaxCount shouldBe 1
            visceraAction.validSacrificeTargets.size shouldBe 1
            check(visceraAction.sourceEntityId in visceraAction.validSacrificeTargets) {
                "The public sacrifice domain omitted the source candidate: $visceraAction"
            }
            println(
                "VISCERA_SEER_PUBLIC_DOMAIN " +
                    "targets=${visceraAction.validSacrificeTargets} " +
                    "count=${visceraAction.sacrificeCount} " +
                    "min=${visceraAction.sacrificeMinCount} " +
                    "max=${visceraAction.sacrificeMaxCount}",
            )
            val actorCreatureIds = observation.zones
                .filter { zone -> zone.zoneType == Zone.BATTLEFIELD && !zone.hidden }
                .flatMap { zone -> zone.cards }
                .filter { card ->
                    card.controllerId == observation.agentToAct &&
                        "CREATURE" in card.types
                }
                .map { card -> card.entityId }
                .sortedBy { id -> id.value }
            visceraAction.validSacrificeTargets.sortedBy { id -> id.value } shouldBe actorCreatureIds
        } finally {
            service.dispose(service.listEnvs())
        }
    }

    test("seed zero original reproducer stops at the first current finding") {
        val service = MultiEnvService(exactPairRegistry())
        try {
            val result = runEpisode(
                service = service,
                policy = DeterministicExternalPolicy(),
                episode = EpisodeConfig(
                    seed = 0L,
                    startingPlayerIndex = 0,
                    seat0 = "Akiri",
                    seat1 = "Chevill",
                    rosterLabel = "Akiri-vs-Chevill",
                ),
            )
            println("ENVIRONMENT_V1_SEED_ZERO_REPRODUCER\n$result")
            result.failure?.let { failure ->
                error("Seed-zero reproducer stopped at the first current finding: $failure")
            }
        } finally {
            service.dispose(service.listEnvs())
        }
    }

    test("runs the exact 72-episode trusted corpus with first-gap stop semantics")
        .config(timeout = 30.minutes) {
        val evidence = runExactPairCorpus()
        println(evidence.render())

        evidence.firstFailure?.let { failure ->
            System.err.println("ENVIRONMENT_V1_CORPUS_FIRST_FAILURE\n${evidence.render()}")
            error("Environment V1 corpus stopped at first real finding: $failure")
        }
        evidence.episodesStarted shouldBe 72
        evidence.terminalEpisodes + evidence.truncatedEpisodes shouldBe 72
        evidence.totalExternalTransitions shouldBe evidence.episodeTransitions.sum()
    }

    test("exact-pair replay gate replays complete semantic trajectories")
        .config(timeout = 15.minutes) {
        val evidence = runExactPairReplayGate()
        println(evidence.render())
        check(evidence.failures.isEmpty()) {
            "Exact-pair replay gate failed: ${evidence.failures.joinToString()}"
        }
        evidence.traces.size shouldBe 4
    }

    test("final exact-pair privacy gate audits both player perspectives")
        .config(timeout = 15.minutes) {
        val evidence = runExactPairPrivacyGate()
        println(evidence.render())
        check(evidence.failures.isEmpty()) {
            "Exact-pair privacy gate failed: ${evidence.failures.joinToString()}"
        }
        evidence.perspectives.size shouldBe 2
        check(evidence.observations > 0) { "Privacy gate did not inspect any observations" }
    }

    test("static reachable decision-family closure is explicit") {
        val evidence = decisionClosureEvidence()
        println(evidence.render())
        check(evidence.uncovered.isEmpty()) {
            "Decision-family closure has uncovered entries: ${evidence.uncovered}"
        }
    }

    test("Issue 56 is proven unreachable from the locked exact pair") {
        val evidence = issue56ReachabilityEvidence()
        println(evidence.render())
        evidence.result shouldBe Issue56Result.PROVEN_UNREACHABLE_EXACT_PAIR
    }
}) {
    private companion object {
        const val AKIRI_SHA256 =
            "0C5878E3B393A2CB6317FBE64E0827E4E9A562A0346E5A75820F11081F0909C6"
        const val CHEVILL_SHA256 =
            "D158760D404F32C32110C377B1CA6E3EF9406FD6E0CC29B620CB5BCF573AC8B2"
        const val MAX_STEPS = 2_000

        private val replayCases = listOf(
            EpisodeConfig(0L, 0, "Akiri", "Chevill", "Akiri-vs-Chevill"),
            EpisodeConfig(0L, 1, "Akiri", "Chevill", "Akiri-vs-Chevill"),
            EpisodeConfig(0L, 0, "Chevill", "Akiri", "Chevill-vs-Akiri"),
            EpisodeConfig(0L, 1, "Chevill", "Akiri", "Chevill-vs-Akiri"),
        )

        private val replayActionSerialization = Json {
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
            ignoreUnknownKeys = false
        }

        fun runExactPairReplayGate(): ReplayGateEvidence {
            val traces = mutableListOf<ReplayTrace>()
            val authoritative = mutableListOf<AuthoritativeReplayCase>()
            val failures = mutableListOf<String>()
            for (episode in replayCases) {
                try {
                    val trace = captureReplayTrace(episode)
                    traces += trace
                    authoritative += CompactReplayBridge.verify(
                        trace = trace,
                        registry = exactPairRegistry(),
                        repositoryRoot = repositoryRoot(),
                    )
                    replayTrace(trace)
                } catch (failure: Exception) {
                    failures += "${episode.rosterLabel}/seed=${episode.seed}/" +
                        "starting=${episode.startingPlayerIndex}: ${failure.message}"
                }
            }
            return ReplayGateEvidence(
                traces = traces,
                authoritative = authoritative,
                failures = failures,
            )
        }

        private fun captureReplayTrace(
            episode: EpisodeConfig,
            captureAuthoritativeReplay: Boolean = true,
        ): ReplayTrace {
            val registry = exactPairRegistry()
            val resolver = DeckResolver(registry)
            val gameConfig = episode.replayGameConfig(resolver)
            val environment = GameEnvironment.create(
                registry,
                executionMode = GameEnvironmentMode.TRUSTED,
            )
            environment.reset(gameConfig, MAX_STEPS)
            val gym = GameGymEnv(
                environment = environment,
                perspectivePlayerIndex = 0,
                observationBuilder = ObservationBuilder(cardRegistry = registry),
            )
            val decisions = mutableListOf<ReplayDecision>()
            val frames = mutableListOf<ReplayFrame>()
            val actions = mutableListOf<GameAction>()
            val checkpoints = mutableListOf<ReplayCheckpointData>()
            val requiredPayloadFields = mutableSetOf<String>()
            val checkpointCadence = if (captureAuthoritativeReplay) {
                CompactReplayBridge.checkpointCadence(repositoryRoot())
            } else {
                0
            }
            var result = gym.observe()
            var observation = result.observation as? TrainingObservation
                ?: error("Replay capture requires a TrainingObservation after reset")
            assertEnvironmentDiagnosticsZero(environment)
            frames += replayFrame(observation)
            if (captureAuthoritativeReplay) {
                checkpoints += ReplayCheckpointData(
                    afterActionCount = 0,
                    fingerprint = authoritativeReplayFingerprint(environment.state),
                )
            }

            val policy = DeterministicExternalPolicy()
            var policyState = DeterministicPolicyState(policySeed(episode))
            var transitions = 0
            while (!observation.terminated && !observation.truncated) {
                check(transitions < MAX_STEPS) {
                    "Replay capture exceeded configured maxSteps=$MAX_STEPS"
                }
                val choice = policy.choose(observation, policyState)
                policyState = policyState.afterChoice()
                result = when (choice) {
                    is SemanticChoice.Action -> {
                        val action = observation.legalActions.singleOrNull {
                            it.actionId == choice.actionId
                        } ?: error(
                            "Replay capture policy action handle was not in the current public list",
                        )
                        val semanticKey = semanticActionKey(action)
                        val semanticOrdinal = semanticActionOrdinal(observation, action, semanticKey)
                        // Only fields on the action actually selected by the external policy are
                        // reachability evidence.  Other legal candidates are public alternatives,
                        // not executed corpus decisions; counting their template-only fields here
                        // would turn an unselected optional action shape into a false closure gap.
                        requiredPayloadFields += action.requiredPayloadFields
                        decisions += ReplayDecision.Action(
                            kind = action.kind,
                            semanticKey = semanticKey,
                            semanticOrdinal = semanticOrdinal,
                            payload = choice.payload,
                        )
                        val resolved = result.registry.resolve(action.actionId)
                        when (resolved) {
                            is ResolvedAction.Legal -> {
                                actions += materializeReplayAction(resolved.action, choice.payload)
                            }

                            is ResolvedAction.Decision -> {
                                val actor = observation.agentToAct
                                    ?: error("Folded decision action has no public actor")
                                actions += SubmitDecision(actor, resolved.response)
                            }

                            ResolvedAction.Unknown -> error(
                                "Replay capture action handle did not resolve in the public registry",
                            )
                        }
                        if (choice.payload == null) {
                            gym.step(action.actionId)
                        } else {
                            gym.step(action.actionId, choice.payload)
                        }
                    }

                    is SemanticChoice.Structured -> {
                        val pending = observation.pendingDecision
                            ?: error("Replay capture structured choice has no pending decision")
                        val decisionId = pending.decisionId
                            ?: error("Replay capture structured choice has no decision ID")
                        val response = toDecisionResponse(decisionId, choice.selection)
                        decisions += ReplayDecision.Structured(
                            family = pending.kind.name,
                            selection = choice.selection,
                        )
                        actions += SubmitDecision(pending.playerId, response)
                        gym.submitDecision(response, actorId = observation.agentToAct)
                    }

                    is SemanticChoice.Gap -> error(
                        "Replay capture encountered policy gap at transition $transitions: $choice",
                    )
                }
                transitions++
                assertEnvironmentDiagnosticsZero(environment)
                observation = result.observation as? TrainingObservation
                    ?: error("Replay capture lost TrainingObservation after transition $transitions")
                frames += replayFrame(observation)
                if (captureAuthoritativeReplay && transitions % checkpointCadence == 0) {
                    checkpoints += ReplayCheckpointData(
                        afterActionCount = transitions,
                        fingerprint = authoritativeReplayFingerprint(environment.state),
                    )
                }
            }

            check(frames.size == decisions.size + 1) {
                "Replay capture frame/decision cardinality mismatch"
            }
            if (captureAuthoritativeReplay &&
                checkpoints.lastOrNull()?.afterActionCount != actions.size
            ) {
                checkpoints += ReplayCheckpointData(
                    afterActionCount = actions.size,
                    fingerprint = authoritativeReplayFingerprint(environment.state),
                )
            }
            if (captureAuthoritativeReplay) {
                check(checkpoints.lastOrNull()?.afterActionCount == actions.size) {
                    "Replay capture is missing the authoritative tail checkpoint"
                }
            }
            return ReplayTrace(
                episode = episode,
                frames = frames,
                decisions = decisions,
                actions = actions,
                checkpoints = checkpoints,
                gameConfig = gameConfig,
                playerIds = environment.playerIds,
                requiredPayloadFields = requiredPayloadFields,
            )
        }

        private fun replayTrace(trace: ReplayTrace) {
            val registry = exactPairRegistry()
            val environment = GameEnvironment.create(
                registry,
                executionMode = GameEnvironmentMode.TRUSTED,
            )
            environment.reset(trace.gameConfig, MAX_STEPS)
            val gym = GameGymEnv(
                environment = environment,
                perspectivePlayerIndex = 0,
                observationBuilder = ObservationBuilder(cardRegistry = registry),
            )
            var result = gym.observe()
            var observation = result.observation as? TrainingObservation
                ?: error("Replay requires a TrainingObservation after reset")
            assertReplayFrame(trace, 0, observation, environment)

            trace.decisions.forEachIndexed { index, decision ->
                result = when (decision) {
                    is ReplayDecision.Action -> {
                        val candidates = externallySelectableActions(observation)
                            .filter { semanticActionKey(it) == decision.semanticKey }
                        val action = candidates.getOrNull(decision.semanticOrdinal)
                            ?: error(
                                "Replay action semantic candidate missing at transition $index: " +
                                    "${decision.semanticKey} ordinal=${decision.semanticOrdinal}",
                            )
                        check(action.kind == decision.kind) {
                            "Replay action kind changed at transition $index: " +
                                "${action.kind} != ${decision.kind}"
                        }
                        if (decision.payload == null) {
                            gym.step(action.actionId)
                        } else {
                            gym.step(action.actionId, decision.payload)
                        }
                    }

                    is ReplayDecision.Structured -> {
                        val pending = observation.pendingDecision
                            ?: error("Replay structured decision missing at transition $index")
                        check(pending.kind.name == decision.family) {
                            "Replay decision family changed at transition $index: " +
                                "${pending.kind.name} != ${decision.family}"
                        }
                        val decisionId = pending.decisionId
                            ?: error("Replay decision has no current public decision ID")
                        gym.submitDecision(
                            response = toDecisionResponse(decisionId, decision.selection),
                            actorId = observation.agentToAct,
                        )
                    }
                }
                assertEnvironmentDiagnosticsZero(environment)
                observation = result.observation as? TrainingObservation
                    ?: error("Replay lost TrainingObservation at transition ${index + 1}")
                assertReplayFrame(trace, index + 1, observation, environment)
            }

            check(observation.terminated || observation.truncated) {
                "Replay ended before the captured terminal/truncated result"
            }
        }

        private fun assertReplayFrame(
            trace: ReplayTrace,
            index: Int,
            observation: TrainingObservation,
            environment: GameEnvironment,
        ) {
            val expected = trace.frames.getOrNull(index)
                ?: error("Replay produced an unexpected frame at index $index")
            val actual = replayFrame(observation)
            check(actual == expected) {
                "Replay semantic frame mismatch at index $index for ${trace.episode}: " +
                    "expectedDigest=${expected.stateDigest}, actualDigest=${actual.stateDigest}"
            }
            check(StateDigest.compute(observation) == observation.stateDigest) {
                "Replay observation digest is not self-consistent at frame $index"
            }
            assertEnvironmentDiagnosticsZero(environment)
        }

        private fun assertEnvironmentDiagnosticsZero(environment: GameEnvironment) {
            val diagnostics = environment.diagnostics
            check(diagnostics.unsupportedCardCount == 0) {
                "Replay observed UNSUPPORTED_CARD=${diagnostics.unsupportedCardCount}"
            }
            check(diagnostics.unsupportedDecisionCount == 0) {
                "Replay observed UNSUPPORTED_DECISION=${diagnostics.unsupportedDecisionCount}"
            }
            check(diagnostics.unsupportedRuleCount == 0) {
                "Replay observed UNSUPPORTED_RULE_OR_MECHANIC=" +
                    diagnostics.unsupportedRuleCount
            }
            check(diagnostics.nativePolicyFallbackCount == 0) {
                "Replay observed NATIVE_POLICY_FALLBACK=${diagnostics.nativePolicyFallbackCount}"
            }
        }

        /**
         * Records the exact engine action accepted by the trusted Gym adapter.  This is a replay
         * recorder, not policy logic: the policy has already chosen through the public observation
         * and the current ActionRegistry is used only to obtain the transport-bound template that
         * the Gym adapter itself will execute.
         */
        private fun materializeReplayAction(
            template: GameAction,
            payload: JsonObject?,
        ): GameAction {
            if (payload == null) return template
            val templateJson = replayActionSerialization
                .encodeToJsonElement(GameAction.serializer(), template)
                .jsonObject
            val merged = buildJsonObject {
                templateJson.forEach { (key, value) -> put(key, value) }
                payload.forEach { (key, value) ->
                    if (key != "abilityKey") put(key, value)
                }
            }
            return replayActionSerialization.decodeFromJsonElement(GameAction.serializer(), merged)
        }

        /** Fingerprints are obtained from the existing CompactReplay implementation, not copied. */
        private fun authoritativeReplayFingerprint(state: com.wingedsheep.engine.state.GameState): String =
            CompactReplayBridge.fingerprint(state)

        private fun replayFrame(observation: TrainingObservation): ReplayFrame = ReplayFrame(
            semanticObservation = ObservationCanonicalizer.semanticJson(observation),
            stateDigest = observation.stateDigest,
            terminated = observation.terminated,
            truncated = observation.truncated,
        )

        private fun semanticActionOrdinal(
            observation: TrainingObservation,
            selected: LegalActionView,
            semanticKey: String,
        ): Int = externallySelectableActions(observation)
            .filter { semanticActionKey(it) == semanticKey }
            .indexOfFirst { it.actionId == selected.actionId }
            .also { check(it >= 0) { "Selected public action was not semantically indexed" } }

        private fun semanticActionKey(action: LegalActionView): String =
            canonicalSemanticJson(ObservationCanonicalizer.semanticActionFingerprint(action))

        private fun externallySelectableActions(
            observation: TrainingObservation,
        ): List<LegalActionView> = observation.legalActions
            .filter { it.affordable || it.isDecisionOption }
            .sortedWith(
                compareBy(
                    { if (it.kind.contains("Pass", ignoreCase = true) ||
                        it.description.contains("pass priority", ignoreCase = true)
                    ) 1 else 0 },
                    { it.kind },
                    { canonicalSemanticJson(it.actionSemantics ?: JsonNull) },
                    { it.sourceEntityId?.value ?: "" },
                    { it.targetEntityIds.joinToString(",") { id -> id.value } },
                ),
            )

        private fun canonicalSemanticJson(element: JsonElement, propertyName: String? = null): String =
            when (element) {
                is JsonObject -> JsonObject(
                    element.entries
                        .sortedBy { it.key }
                        .associate { (key, value) ->
                            key to Json.parseToJsonElement(
                                canonicalSemanticJson(value, key),
                            )
                        },
                ).toString()

                is JsonArray -> {
                    val values = element.map { canonicalSemanticJson(it) }
                    val ordered = if (propertyName in semanticUnorderedArrayKeys) {
                        values.sorted()
                    } else {
                        values
                    }
                    "[${ordered.joinToString(",")}]"
                }

                else -> element.toString()
            }

        private val semanticUnorderedArrayKeys = setOf(
            "types",
            "subtypes",
            "colors",
            "keywords",
            "availableColors",
            "attachments",
            "targetEntityIds",
            "validSacrificeTargets",
            "candidates",
            "nonSelectableOptions",
            "matchingOptions",
            "availableSources",
            "waterbendPermanents",
            "producesColors",
            "sourceSubtypes",
            "sourceBuckets",
            "sourceColorBuckets",
            "certifiedFloatingBuckets",
            "blockedByIds",
            "blockedAttackerIds",
        )

        fun runExactPairPrivacyGate(): PrivacyGateEvidence {
            val cases = listOf(
                EpisodeConfig(0L, 0, "Akiri", "Chevill", "Akiri-vs-Chevill"),
                EpisodeConfig(0L, 1, "Chevill", "Akiri", "Chevill-vs-Akiri"),
            )
            val registry = exactPairRegistry()
            val resolver = DeckResolver(registry)
            val observations = mutableListOf<TrainingObservation>()
            val failures = mutableListOf<String>()
            for (episode in cases) {
                val environment = GameEnvironment.create(
                    registry,
                    executionMode = GameEnvironmentMode.TRUSTED,
                )
                try {
                    environment.reset(episode.gameConfig(resolver), MAX_STEPS)
                    val builder = ObservationBuilder(cardRegistry = registry)
                    val gym = GameGymEnv(
                        environment = environment,
                        perspectivePlayerIndex = 0,
                        observationBuilder = builder,
                    )
                    var policyObservation = gym.observe().observation as? TrainingObservation
                        ?: error("Privacy run requires a TrainingObservation after reset")
                    var policyState = DeterministicPolicyState(policySeed(episode))
                    var transitions = 0

                    fun auditAllPerspectives() {
                        val legalActions = environment.legalActions()
                        environment.playerIds.forEach { perspective ->
                            val result = builder.build(
                                state = environment.state,
                                perspectivePlayerId = perspective,
                                legalActions = legalActions,
                                truncated = environment.isTruncated,
                            )
                            check(result.diagnostics.isEmpty()) {
                                "Privacy projection diagnostics for $perspective: " +
                                    result.diagnostics
                            }
                            val observation = result.observation as? TrainingObservation
                                ?: error("Privacy projection requires TrainingObservation")
                            auditPublicObservation(observation)
                            observations += observation
                        }
                        check(environment.diagnostics.events.isEmpty()) {
                            "Privacy audit observed diagnostics: ${environment.diagnostics.events}"
                        }
                    }

                    auditAllPerspectives()
                    while (!policyObservation.terminated && !policyObservation.truncated) {
                        check(transitions < MAX_STEPS) {
                            "Privacy run exceeded configured maxSteps=$MAX_STEPS"
                        }
                        val choice = DeterministicExternalPolicy().choose(
                            policyObservation,
                            policyState,
                        )
                        policyState = policyState.afterChoice()
                        val result = when (choice) {
                            is SemanticChoice.Action -> choice.payload?.let { payload ->
                                gym.step(choice.actionId, payload)
                            } ?: gym.step(choice.actionId)

                            is SemanticChoice.Structured -> {
                                val pending = policyObservation.pendingDecision
                                    ?: error("Privacy structured choice has no pending decision")
                                val decisionId = pending.decisionId
                                    ?: error("Privacy structured choice has no decision ID")
                                gym.submitDecision(
                                    toDecisionResponse(decisionId, choice.selection),
                                    actorId = policyObservation.agentToAct,
                                )
                            }

                            is SemanticChoice.Gap -> error(
                                "Privacy run encountered policy gap at transition $transitions: $choice",
                            )
                        }
                        transitions++
                        policyObservation = result.observation as? TrainingObservation
                            ?: error("Privacy run lost TrainingObservation after transition $transitions")
                        auditAllPerspectives()
                    }
                    check(policyObservation.terminated || policyObservation.truncated) {
                        "Privacy run did not reach terminal/truncated state"
                    }
                } catch (failure: Exception) {
                    failures += "${episode.rosterLabel}/starting=${episode.startingPlayerIndex}: " +
                        (failure.message ?: failure::class.simpleName.orEmpty())
                } finally {
                    // The direct environment is a test oracle only; policy input above is always
                    // the remapped public observation returned by GameGymEnv.
                }
            }
            return PrivacyGateEvidence(
                perspectives = observations.map { it.perspectivePlayerId.value }.toSet(),
                observations = observations.size,
                failures = failures,
            )
        }

        private fun auditPublicObservation(observation: TrainingObservation) {
            val players = observation.players.map { it.id }.toSet()
            check(players.size == observation.players.size) {
                "Privacy observation contains duplicate player IDs"
            }
            val addressable = buildSet {
                addAll(players)
                observation.zones.flatMapTo(this) { zone -> zone.cards.map { it.entityId } }
                observation.stack.forEach { item ->
                    add(item.entityId)
                    item.controllerId?.let(::add)
                    item.sourceEntityId?.let(::add)
                    addAll(item.targets)
                }
            }

            fun requireAddressable(id: EntityId?, path: String) {
                if (id != null) {
                    check(id in addressable) {
                        "Privacy observation leaked an unaddressable reference at $path: $id"
                    }
                }
            }

            check(observation.perspectivePlayerId in players) {
                "Privacy perspective is not one of the public players"
            }
            requireAddressable(observation.agentToAct, "agentToAct")
            requireAddressable(observation.activePlayerId, "activePlayerId")
            requireAddressable(observation.priorityPlayerId, "priorityPlayerId")
            requireAddressable(observation.winnerId, "winnerId")

            observation.zones.forEach { zone ->
                requireAddressable(zone.ownerId, "zone.ownerId")
                zone.cards.forEach { card ->
                    requireAddressable(card.entityId, "zone.${zone.zoneType}.card.entityId")
                    requireAddressable(card.ownerId, "zone.${zone.zoneType}.card.ownerId")
                    requireAddressable(card.controllerId, "zone.${zone.zoneType}.card.controllerId")
                    requireAddressable(card.attachedTo, "zone.${zone.zoneType}.card.attachedTo")
                    card.attachments.forEach { id -> requireAddressable(id, "card.attachments") }
                    if (zone.zoneType == Zone.HAND || zone.zoneType == Zone.LIBRARY) {
                        check(!zone.hidden || card.cardDefinitionId != null) {
                            "Publicly emitted hidden-zone card has no visibility marker"
                        }
                    }
                    if (card.faceDown && card.ownerId != observation.perspectivePlayerId) {
                        check(card.cardDefinitionId == null) {
                            "Opponent face-down card identity was published"
                        }
                    }
                }
            }

            observation.legalActions.forEachIndexed { index, action ->
                requireAddressable(action.sourceEntityId, "legalActions[$index].sourceEntityId")
                action.targetEntityIds.forEach { id ->
                    requireAddressable(id, "legalActions[$index].targetEntityIds")
                }
                action.validSacrificeTargets.forEach { id ->
                    requireAddressable(id, "legalActions[$index].validSacrificeTargets")
                }
                action.targetDomain?.requirements.orEmpty().forEach { requirement ->
                    requirement.candidates.forEach { id ->
                        requireAddressable(id, "legalActions[$index].targetDomain.candidates")
                    }
                }
                action.attackDeclarationDomain?.let { domain ->
                    domain.attackerToDefenders.forEach { (attacker, defenders) ->
                        requireAddressable(attacker, "attackDeclarationDomain.attacker")
                        defenders.forEach { id ->
                            requireAddressable(id, "attackDeclarationDomain.defender")
                        }
                    }
                    domain.mandatoryAttackers.forEach { id ->
                        requireAddressable(id, "attackDeclarationDomain.mandatory")
                    }
                    domain.coAttackerRequirements.values.flatten().flatMap { it.anyOf }
                        .forEach { id -> requireAddressable(id, "attackDeclarationDomain.anyOf") }
                    domain.bandConstraints.bandingAttackersByDefender.forEach { (defender, attackers) ->
                        requireAddressable(defender, "attackDeclarationDomain.band.defender")
                        attackers.forEach { id ->
                            requireAddressable(id, "attackDeclarationDomain.band.attacker")
                        }
                    }
                    domain.bandConstraints.nonBandingAttackersByDefender.forEach { (defender, attackers) ->
                        requireAddressable(defender, "attackDeclarationDomain.nonBand.defender")
                        attackers.forEach { id ->
                            requireAddressable(id, "attackDeclarationDomain.nonBand.attacker")
                        }
                    }
                }
                action.paymentDomain?.let { domain ->
                    domain.sourceActivations.forEach { source ->
                        requireAddressable(source.sourceId, "paymentDomain.sourceActivations")
                    }
                    domain.currentPool.certifiedFloatingBuckets.forEach { bucket ->
                        requireAddressable(bucket.sourceId, "paymentDomain.floatingBucket")
                    }
                }
                action.actionSemantics?.let { semantics ->
                    semanticEntityReferences(semantics).forEach { id ->
                        requireAddressable(id, "legalActions[$index].actionSemantics")
                    }
                }
            }

            observation.pendingDecision?.let { pending ->
                requireAddressable(pending.playerId, "pendingDecision.playerId")
                requireAddressable(pending.sourceEntityId, "pendingDecision.sourceEntityId")
                requireAddressable(pending.triggeringEntityId, "pendingDecision.triggeringEntityId")
                pending.structuredDomain?.let { domain ->
                    structuredEntityReferences(domain).forEach { id ->
                        requireAddressable(id, "pendingDecision.structuredDomain")
                    }
                }
                if (pending.playerId != observation.perspectivePlayerId) {
                    check(pending.structuredDomain == null) {
                        "Non-owner perspective received a structured decision domain"
                    }
                    check(observation.legalActions.isEmpty()) {
                        "Non-owner perspective received legal actions"
                    }
                }
            }

            val wire = Json.parseToJsonElement(ObservationCanonicalizer.wireJson(observation))
            val forbiddenKeys = collectJsonKeys(wire).filter {
                it.equals("diagnostics", ignoreCase = true) ||
                    it.equals("registry", ignoreCase = true) ||
                    it.equals("gameState", ignoreCase = true) ||
                    it.equals("internalState", ignoreCase = true) ||
                    it.equals("debugState", ignoreCase = true)
            }
            check(forbiddenKeys.isEmpty()) {
                "Privacy wire observation contains internal/debug fields: $forbiddenKeys"
            }
            check(StateDigest.compute(observation) == observation.stateDigest) {
                "Privacy observation stateDigest is not self-consistent"
            }
        }

        private fun semanticEntityReferences(element: JsonElement): Set<EntityId> {
            val referenceKeys = setOf(
                "entityId",
                "entityIds",
                "sourceEntityId",
                "targetEntityId",
                "targetEntityIds",
                "playerId",
                "sourceId",
                "triggeringEntityId",
                "cardId",
                "spellEntityId",
                "permanentId",
                "attackerId",
                "defenderId",
                "sacrificedPermanents",
                "tappedPermanents",
            )

            fun values(value: JsonElement): List<String> = when (value) {
                is JsonPrimitive -> if (value.isString) listOf(value.content) else emptyList()
                is JsonArray -> value.flatMap(::values)
                is JsonObject -> value.values.flatMap(::values)
                JsonNull -> emptyList()
            }

            fun walk(value: JsonElement): Set<EntityId> = when (value) {
                is JsonObject -> value.entries.flatMapTo(mutableSetOf()) { (key, child) ->
                    val direct = if (key in referenceKeys) {
                        values(child).map(::EntityId)
                    } else {
                        emptyList()
                    }
                    direct + walk(child)
                }
                is JsonArray -> value.flatMapTo(mutableSetOf(), ::walk)
                else -> emptySet()
            }
            return walk(element)
        }

        private fun structuredEntityReferences(domain: StructuredDecisionDomain): Set<EntityId> =
            buildSet {
                when (domain) {
                    is TargetsDomain -> domain.requirements.flatMapTo(this) { it.candidates }
                    is CardSelectionDomain -> {
                        addAll(domain.options)
                        addAll(domain.nonSelectableOptions)
                        domain.cardInfo?.keys?.let(::addAll)
                        domain.conditionalMinimums.flatMapTo(this) { it.matchingOptions }
                    }
                    is ModeSelectionDomain -> Unit
                    is DistributionDomain -> {
                        addAll(domain.targets)
                        addAll(domain.maxPerTarget.keys)
                    }
                    is OrderingDomain -> {
                        addAll(domain.objects)
                        domain.cardInfo?.keys?.let(::addAll)
                        domain.objectLabels?.keys?.let(::addAll)
                    }
                    is SplitPilesDomain -> {
                        addAll(domain.cards)
                        domain.cardInfo?.keys?.let(::addAll)
                    }
                    is SearchLibraryDomain -> {
                        addAll(domain.options)
                        addAll(domain.cards.keys)
                    }
                    is ReorderLibraryDomain -> {
                        addAll(domain.cards)
                        addAll(domain.cardInfo.keys)
                    }
                    is CombatResolutionDomain -> {
                        domain.attackers.flatMapTo(this) { attacker ->
                            buildList {
                                add(attacker.id)
                                addAll(attacker.blockedByIds)
                                add(attacker.attackedDefenderId)
                            }
                        }
                        domain.blockers.flatMapTo(this) { blocker ->
                            buildList {
                                add(blocker.id)
                                addAll(blocker.blockedAttackerIds)
                            }
                        }
                        domain.defenders.forEach { add(it.id) }
                        domain.edges.forEach { edge ->
                            add(edge.sourceId)
                            add(edge.targetId)
                            add(edge.editableBy)
                        }
                        domain.coChooserId?.let(::add)
                    }
                    is ManaSourcesDomain -> {
                        domain.availableSources.mapTo(this) { it.entityId }
                        domain.waterbendPermanents.mapTo(this) { it.entityId }
                    }
                    is ReplacementDomain -> domain.fromMetadata
                        .plus(domain.toMetadata)
                        .mapNotNullTo(this) { it.triggeringPlayerId }
                    is BudgetModalDomain -> Unit
                }
            }

        private fun collectJsonKeys(element: JsonElement): Set<String> = when (element) {
            is JsonObject -> element.entries.flatMapTo(mutableSetOf()) { (key, value) ->
                setOf(key) + collectJsonKeys(value)
            }
            is JsonArray -> element.flatMapTo(mutableSetOf(), ::collectJsonKeys)
            else -> emptySet()
        }

        fun decisionClosureEvidence(): DecisionClosureEvidence {
            val boundedEvidence = replayCases.map { episode ->
                captureReplayTrace(episode, captureAuthoritativeReplay = false)
            }
            val corpusSnapshot = exactPairCorpusReachabilitySnapshot()
            val observedActionKinds = corpusSnapshot.actionKinds.toMutableMap()
            val observedDecisionFamilies = corpusSnapshot.decisionFamilies.toMutableMap()
            boundedEvidence.flatMap { trace ->
                trace.decisions.mapNotNull { decision ->
                    (decision as? ReplayDecision.Action)?.kind
                }
            }.forEach { kind ->
                observedActionKinds[kind] = (observedActionKinds[kind] ?: 0) + 1
            }
            boundedEvidence.flatMap { trace ->
                trace.decisions.mapNotNull { decision ->
                    (decision as? ReplayDecision.Structured)?.family
                }
            }.forEach { family ->
                observedDecisionFamilies[family] = (observedDecisionFamilies[family] ?: 0) + 1
            }
            val observedRequiredPayloadFields = buildSet {
                addAll(corpusSnapshot.requiredPayloadFields)
                boundedEvidence.forEach { addAll(it.requiredPayloadFields) }
            }
            val missingFieldHandlers = observedRequiredPayloadFields
                .filterNot { it in EXTERNAL_POLICY_SUPPORTED_REQUIRED_PAYLOAD_FIELDS }
                .toList()
                .sorted()
            val observedFamilies = (observedActionKinds.keys + observedDecisionFamilies.keys)
                .toSortedSet()
            val dispositionByFamily = buildMap {
                PUBLIC_ACTION_DOMAIN_FAMILIES.forEach {
                    put(it, "SUPPORTED_BY_PUBLIC_ACTION_DOMAIN")
                }
                STRUCTURED_DECISION_DOMAIN_FAMILIES.forEach {
                    put(it, "SUPPORTED_BY_STRUCTURED_DECISION_DOMAIN")
                }
            }
            val rows = observedFamilies.map { family ->
                ClosureRow(
                    family = family,
                    observedCount = (observedActionKinds[family] ?: 0) +
                        (observedDecisionFamilies[family] ?: 0),
                    disposition = dispositionByFamily[family] ?: "UNCLASSIFIED",
                )
            }
            val uncovered = buildList {
                addAll(missingFieldHandlers.map { "requiredPayloadFields.$it" })
                addAll(observedFamilies.filter { it !in dispositionByFamily })
            }

            val lockedCards = (readLockedDeck("akiri-v0.1.txt").cards +
                readLockedDeck("chevill-v0.1.txt").cards).distinct()
            val resolvedCards = lockedCards.mapNotNull(exactPairRegistry()::getCard).distinctBy { it.name }
            check(resolvedCards.size == 146) {
                "Decision closure static card set resolved ${resolvedCards.size}, expected 146"
            }
            val definitionScan = scanLockedDefinitions(resolvedCards)
            check(definitionScan.cardCount == 146) {
                "Decision closure scanned ${definitionScan.cardCount} definitions, expected 146"
            }
            check(definitionScan.emptySerializations.isEmpty()) {
                "Decision closure found empty serialized definitions: " +
                    definitionScan.emptySerializations
            }
            check(definitionScan.digest == corpusSnapshot.definitionDigest) {
                "Decision closure corpus telemetry is stale for the current locked definitions: " +
                    "recorded=${corpusSnapshot.definitionDigest}, " +
                    "current=${definitionScan.digest}"
            }

            return DecisionClosureEvidence(
                rows = rows,
                resolvedCards = resolvedCards.size,
                uncovered = uncovered,
                sourceBoundary = listOf(
                    "derived-action-families=${observedActionKinds.keys.sorted()}",
                    "derived-decision-families=${observedDecisionFamilies.keys.sorted()}",
                    "corpus-snapshot-total-transitions=${corpusSnapshot.totalTransitions}",
                    "bounded-current-evidence-cases=${boundedEvidence.size}",
                    "static-definition-scan=${definitionScan.cardCount}; " +
                        "digest=${definitionScan.digest}",
                    "recorded-corpus-telemetry-bound-to-definition-digest=" +
                        corpusSnapshot.definitionDigest,
                    "closure-families-derived-from-recorded-and-bounded-observations",
                    "closure-counts-are-snapshot-evidence-not-family-authority",
                    "policy-supported-required-fields=" +
                        EXTERNAL_POLICY_SUPPORTED_REQUIRED_PAYLOAD_FIELDS.sorted(),
                    "policy-input=TrainingObservation",
                    "policy-input=public legal action/actionSemantics",
                    "policy-input=public PendingDecision structuredDomain",
                    "unknown required fields fail closed",
                    "unknown observed families fail closed",
                ),
            )
        }

        private fun exactPairCorpusReachabilitySnapshot(): ExactPairCorpusReachabilitySnapshot =
            ExactPairCorpusReachabilitySnapshot(
                // These counts are retained as the last completed 72/72 run's evidence only. The
                // family set in decisionClosureEvidence is derived from this recorded telemetry
                // and current bounded observations, never from hand-authored ClosureRow entries.
                actionKinds = mapOf(
                    "ActivateAbility" to 48_522,
                    "CastSpell" to 690,
                    "CastSpellMode" to 76,
                    "CastWithKicker" to 6,
                    "CycleCard" to 44,
                    "DECISION" to 2_697,
                    "DeclareAttackers" to 410,
                    "PassPriority" to 85_006,
                    "PlayLand" to 2_435,
                ),
                decisionFamilies = mapOf(
                    "CHOOSE_COLOR" to 112,
                    "CHOOSE_TARGETS" to 74,
                    "PRIORITY" to 137_189,
                    "SELECT_CARDS" to 2_759,
                    "YES_NO" to 104,
                ),
                requiredPayloadFields = setOf(
                    "paymentStrategy",
                    "xValue",
                    "targets",
                    "manaColorChoice",
                    "additionalCostPayment",
                    "costPayment",
                    "attackers",
                    "bands",
                ),
                totalTransitions = 140_238,
                definitionDigest = "6953A60BEC45B96383942087CB1D95D2CF76F9444A894E8E0916F4E15D27DC19",
            )

        private fun scanLockedDefinitions(cards: List<CardDefinition>): DefinitionScanEvidence {
            val serialized = cards.map { card ->
                card.name to CardSerialization.json.encodeToJsonElement(
                    CardDefinition.serializer(),
                    card,
                ).toString()
            }.sortedBy { it.first }
            val digestInput = serialized.joinToString("\n") { (name, json) -> "$name:$json" }
            return DefinitionScanEvidence(
                cardCount = serialized.size,
                emptySerializations = serialized.filter { it.second.isBlank() }.map { it.first },
                digest = sha256Text(digestInput),
            )
        }

        fun issue56ReachabilityEvidence(): Issue56Evidence {
            val registry = exactPairRegistry()
            val lockedCards = (readLockedDeck("akiri-v0.1.txt").cards +
                readLockedDeck("chevill-v0.1.txt").cards).distinct()
            val cards = lockedCards.map { registry.requireCard(it) }.distinctBy { it.name }
            val retargetingTypes = setOf(
                "Storm",
                "StormCopy",
                "CopyTargetSpell",
                "CopyTargetTriggeredAbility",
                "CopyTargetSpellOrAbility",
                "CopyEachTargetSpell",
                "ChainCopy",
            )
            val sites = cards.flatMap { card ->
                serializedCopyRetargetingSites(card, retargetingTypes)
            }.sortedWith(compareBy<Issue56CopySite> { it.cardName }
                .thenBy { it.type }
                .thenBy { it.path })
            val partialRetargetingCandidates = sites.filter { site ->
                site.targetRequirementCount == null || site.targetRequirementCount > 1
            }

            return Issue56Evidence(
                result = if (partialRetargetingCandidates.isEmpty()) {
                    Issue56Result.PROVEN_UNREACHABLE_EXACT_PAIR
                } else {
                    Issue56Result.REACHABLE_BLOCKER
                },
                resolvedCards = cards.size,
                retargetingSites = sites,
                evidence = listOf(
                    "static locked-card script walk uses CardSerialization with nearest enclosing targetRequirements",
                    "copy-retargeting effect/keyword nodes inspected=${sites.size}",
                    "partial-retargeting candidates=${partialRetargetingCandidates.size}",
                    "partial retargeting requires more than one target slot on the copied spell/ability",
                    "current issue #56 remains an open general Rules issue",
                ),
            )
        }

        private fun serializedCopyRetargetingSites(
            card: CardDefinition,
            retargetingTypes: Set<String>,
        ): List<Issue56CopySite> {
            val sites = mutableListOf<Issue56CopySite>()
            val scopedTargetRequirementCollections = setOf(
                "activatedAbilities",
                "triggeredAbilities",
                "stateTriggeredAbilities",
                "costPaidLinkedTriggers",
                "classLevels",
                "sagaChapters",
            )
            fun targetRequirementCount(element: JsonObject): Int? =
                (element["targetRequirements"] as? JsonArray)?.size

            fun walk(
                element: JsonElement,
                path: String,
                inheritedTargetRequirementCount: Int?,
                scopedTargetRequirementCount: Int? = null,
            ) {
                when (element) {
                    is JsonObject -> {
                        val targetCount = targetRequirementCount(element)
                            ?: scopedTargetRequirementCount
                            ?: inheritedTargetRequirementCount
                        val type = (element["type"] as? JsonPrimitive)?.content
                        if (type in retargetingTypes) {
                            val copyType = type ?: return
                            sites += Issue56CopySite(
                                cardName = card.name,
                                type = copyType,
                                targetRequirementCount = targetCount,
                                path = path,
                            )
                        }
                        element.keys.sorted().forEach { key ->
                            val childScope = when (key) {
                                "script" -> card.script.targetRequirements.size
                                in scopedTargetRequirementCollections -> 0
                                else -> null
                            }
                            walk(element.getValue(key), "$path.$key", targetCount, childScope)
                        }
                    }

                    is JsonArray -> element.forEachIndexed { index, child ->
                        walk(child, "$path[$index]", inheritedTargetRequirementCount, scopedTargetRequirementCount)
                    }

                    else -> Unit
                }
            }

            walk(
                CardSerialization.json.encodeToJsonElement(CardDefinition.serializer(), card),
                "$",
                null,
            )
            return sites
        }

        private fun EpisodeConfig.gameConfig(resolver: DeckResolver): GameConfig {
            val config = envConfig()
            return GameConfig(
                players = config.players.map { player ->
                    PlayerConfig(
                        name = player.name,
                        deck = resolver.resolve(player.deck),
                        startingLife = player.startingLife,
                        playerId = player.playerId,
                        commanderCardName = player.commanderCardName,
                    )
                },
                startingHandSize = config.startingHandSize,
                skipMulligans = config.skipMulligans,
                useHandSmoother = config.useHandSmoother,
                startingPlayerIndex = config.startingPlayerIndex,
                format = config.format,
                seed = config.seed,
            )
        }

        /**
         * ReplaySetup persists player identities.  Use the same explicit identities for the
         * recording and reconstruction so GameInitializer's entity counter advances identically
         * on both sides of the authoritative replay contract.
         */
        private fun EpisodeConfig.replayGameConfig(resolver: DeckResolver): GameConfig =
            gameConfig(resolver).let { config ->
                config.copy(
                    players = config.players.mapIndexed { index, player ->
                        player.copy(playerId = EntityId("a5-replay-player-$index"))
                    },
                )
            }

        fun runExactPairCorpus(): CorpusEvidence {
            val evidence = CorpusEvidence()
            val policy = DeterministicExternalPolicy()
            val service = MultiEnvService(exactPairRegistry())
            try {
                for (episode in corpusCases()) {
                    if (evidence.firstFailure != null) break
                    val result = runEpisode(service, policy, episode)
                    evidence.record(result)
                }
            } finally {
                service.dispose(service.listEnvs())
            }
            return evidence
        }

        fun runEpisode(
            service: MultiEnvService,
            policy: DeterministicExternalPolicy,
            episode: EpisodeConfig,
        ): EpisodeResult {
            val policyState = DeterministicPolicyState(policySeed(episode))
            var state = policyState
            var envId: EnvId? = null
            var observation: TrainingObservation? = null
            var lastFamily = "RESET"
            var lastActionKind = "RESET"
            var lastActionView: LegalActionView? = null
            var lastChoicePayload: JsonObject? = null
            var lastPreTransitionObservation: TrainingObservation? = null
            var transitions = 0
            val actionKinds = TreeMap<String, Int>()
            val decisionFamilies = TreeMap<String, Int>()
            var commanderZoneDecisions = 0
            var paymentDecisions = 0
            var searchDecisions = 0
            var combatDecisions = 0

            fun currentFailure(
                classification: String,
                code: String,
                reason: String,
                diagnostic: String = code,
                publicDomain: String = "not captured",
                proposedFollowUp: String = "No follow-up recorded",
            ): AcceptanceFailure = AcceptanceFailure(
                classification = classification,
                code = code,
                reason = reason,
                diagnostic = diagnostic,
                publicDomain = publicDomain,
                proposedFollowUp = proposedFollowUp,
                seed = episode.seed,
                policySeed = policyState.policySeed,
                roster = episode.rosterLabel,
                startingPlayerIndex = episode.startingPlayerIndex,
                step = transitions,
                actor = observation?.agentToAct?.value,
                stateDigest = observation?.stateDigest,
                decisionFamily = lastFamily,
                actionKind = lastActionKind,
            )

            fun observe(result: ObservationResult): TrainingObservation {
                check(result.diagnostics.isEmpty()) {
                    "The public observation result carried internal diagnostics"
                }
                val game = result.observation as? TrainingObservation
                    ?: error("Exact-pair corpus requires a TrainingObservation")
                observation = game
                return game
            }

            fun paymentDomainOffenderInventory(
                game: TrainingObservation?,
            ): String {
                val offenders = game?.legalActions
                    ?.filter { action -> action.manaCost != null && action.paymentDomain == null }
                    .orEmpty()
                val payableActions = game?.legalActions
                    ?.filter { action -> action.manaCost != null }
                    .orEmpty()
                return buildString {
                    append("UNPUBLISHED_PAYABLE_ACTION_COUNT=${offenders.size}")
                    offenders.forEachIndexed { index, action ->
                        append("; offender[")
                        append(index)
                        append("]=")
                        append(acceptancePublicActionDomain(action, null, game?.pendingDecision))
                    }
                    append("; PUBLIC_PAYABLE_ACTION_COUNT=${payableActions.size}")
                    payableActions.forEachIndexed { index, action ->
                        append("; payable[")
                        append(index)
                        append("]=")
                        append(acceptancePublicActionDomain(action, null, game?.pendingDecision))
                    }
                }
            }

            fun diagnosticFailure(): AcceptanceFailure? {
                val id = envId ?: return null
                val diagnostics = service.diagnostics(id)
                val signal = diagnostics.events.firstOrNull() ?: return null
                val classification = when (signal.kind) {
                    DiagnosticKind.UNSUPPORTED_CARD -> "A9_UNSUPPORTED_CARD"
                    DiagnosticKind.UNSUPPORTED_DECISION -> "A9_UNSUPPORTED_DECISION"
                    DiagnosticKind.UNSUPPORTED_RULE_OR_MECHANIC ->
                        "A9_UNSUPPORTED_RULE_OR_MECHANIC"
                    DiagnosticKind.NATIVE_POLICY_FALLBACK -> "A5_NATIVE_POLICY_FALLBACK"
                }
                return currentFailure(
                    classification = classification,
                    code = signal.semanticCode,
                    reason = when (signal.semanticCode) {
                        "PAYMENT_DOMAIN_UNSUPPORTED" ->
                            "Trusted transition reached a payable legal action without a published PaymentDomainV4"
                        else -> "Authoritative trusted-episode diagnostic was recorded"
                    },
                    diagnostic = signal.semanticCode,
                    publicDomain = when (signal.semanticCode) {
                        "PAYMENT_DOMAIN_UNSUPPORTED" ->
                            "PRE_TRANSITION=${paymentDomainOffenderInventory(lastPreTransitionObservation)}; " +
                                "POST_TRANSITION=${paymentDomainOffenderInventory(observation)}; " +
                                "LAST_SUBMITTED=${acceptancePublicActionDomain(lastActionView, lastChoicePayload, observation?.pendingDecision)}"
                        else -> "authoritative diagnostic event; public domain not captured"
                    },
                    proposedFollowUp = when (signal.semanticCode) {
                        "PAYMENT_DOMAIN_UNSUPPORTED" ->
                            "Publish a complete PaymentDomainV4 for every reachable payable legal action outside #73"
                        else -> "Classify and repair the owning production path outside #73"
                    },
                )
            }

            fun assertDiagnosticsZero(): AcceptanceFailure? {
                val id = envId ?: return null
                val diagnostics = service.diagnostics(id)
                if (diagnostics.unsupportedCardCount == 0 &&
                    diagnostics.unsupportedDecisionCount == 0 &&
                    diagnostics.unsupportedRuleCount == 0 &&
                    diagnostics.nativePolicyFallbackCount == 0
                ) {
                    return null
                }
                return diagnosticFailure()
                    ?: currentFailure(
                        classification = "A9_UNSUPPORTED_RULE_OR_MECHANIC",
                        code = "UNKNOWN_DIAGNOSTIC",
                        reason = "A diagnostic counter became non-zero without a typed event",
                    )
            }

            fun countPublicBoundary(game: TrainingObservation) {
                val pending = game.pendingDecision
                val family = pending?.kind?.name ?: "PRIORITY"
                lastFamily = family
                decisionFamilies[family] = (decisionFamilies[family] ?: 0) + 1
                val publicText = listOf(
                    pending?.prompt,
                    pending?.sourceName,
                    pending?.effectHint,
                ).filterNotNull().joinToString(" ")
                if (publicText.contains("commander", ignoreCase = true) ||
                    family.contains("COMMANDER", ignoreCase = true)
                ) {
                    commanderZoneDecisions++
                }
                if (family == PendingDecisionKind.SELECT_MANA_SOURCES.name ||
                    game.legalActions.any { it.manaCost != null }
                ) {
                    paymentDecisions++
                }
                if (family == PendingDecisionKind.SEARCH_LIBRARY.name ||
                    family.contains("SEARCH", ignoreCase = true)
                ) {
                    searchDecisions++
                }
                if (family == PendingDecisionKind.COMBAT_RESOLUTION.name ||
                    family.contains("COMBAT", ignoreCase = true) ||
                    game.legalActions.any {
                        it.kind.contains("Attack", ignoreCase = true) ||
                            it.kind.contains("Block", ignoreCase = true)
                    }
                ) {
                    combatDecisions++
                }
            }

            return try {
                val created = service.create(episode.envConfig())
                envId = created.envId
                var game = observe(created.observation)
                assertDiagnosticsZero()?.let { failure ->
                    return EpisodeResult(
                        episode = episode,
                        transitions = transitions,
                        terminal = game.terminated,
                        truncated = game.truncated,
                        winner = game.winnerId,
                        actionKinds = actionKinds,
                        decisionFamilies = decisionFamilies,
                        commanderZoneDecisions = commanderZoneDecisions,
                        paymentDecisions = paymentDecisions,
                        searchDecisions = searchDecisions,
                        combatDecisions = combatDecisions,
                        failure = failure,
                    )
                }

                while (!game.terminated && !game.truncated) {
                    if (transitions >= MAX_STEPS) {
                        val failure = currentFailure(
                            classification = "A3_GYM_INTEGRATION_GAP",
                            code = "MAX_STEPS_NOT_REPORTED",
                            reason = "The environment exceeded its configured external horizon",
                        )
                        return EpisodeResult(
                            episode = episode,
                            transitions = transitions,
                            terminal = false,
                            truncated = false,
                            winner = null,
                            actionKinds = actionKinds,
                            decisionFamilies = decisionFamilies,
                            commanderZoneDecisions = commanderZoneDecisions,
                            paymentDecisions = paymentDecisions,
                            searchDecisions = searchDecisions,
                            combatDecisions = combatDecisions,
                            failure = failure,
                        )
                    }
                    check(game.agentToAct != null) {
                        "Nonterminal observation did not publish agentToAct"
                    }
                    if (game.pendingDecision == null && game.legalActions.isEmpty()) {
                        val failure = currentFailure(
                            classification = "A5_DECISION_GAP",
                            code = "EMPTY_EXTERNAL_ACTION_DOMAIN",
                            reason = "Nonterminal priority state published no legal actions",
                        )
                        return EpisodeResult(
                            episode = episode,
                            transitions = transitions,
                            terminal = false,
                            truncated = false,
                            winner = null,
                            actionKinds = actionKinds,
                            decisionFamilies = decisionFamilies,
                            commanderZoneDecisions = commanderZoneDecisions,
                            paymentDecisions = paymentDecisions,
                            searchDecisions = searchDecisions,
                            combatDecisions = combatDecisions,
                            failure = failure,
                        )
                    }

                    countPublicBoundary(game)
                    lastPreTransitionObservation = game
                    val choice = policy.choose(game, state)
                    state = state.afterChoice()
                    lastActionView = null
                    lastChoicePayload = null
                    when (choice) {
                        is SemanticChoice.Gap -> {
                            lastFamily = choice.family
                            lastActionKind = choice.actionKind ?: "DECISION"
                            val failure = currentFailure(
                                classification = choice.classification,
                                code = choice.code,
                                reason = choice.reason,
                                diagnostic = choice.diagnostic,
                                publicDomain = choice.publicDomain,
                                proposedFollowUp = choice.proposedFollowUp,
                            )
                            return EpisodeResult(
                                episode = episode,
                                transitions = transitions,
                                terminal = false,
                                truncated = false,
                                winner = null,
                                actionKinds = actionKinds,
                                decisionFamilies = decisionFamilies,
                                commanderZoneDecisions = commanderZoneDecisions,
                                paymentDecisions = paymentDecisions,
                                searchDecisions = searchDecisions,
                                combatDecisions = combatDecisions,
                                failure = failure,
                            )
                        }

                        is SemanticChoice.Action -> {
                            lastActionKind = choice.kind
                            lastActionView = game.legalActions.firstOrNull { action ->
                                action.actionId == choice.actionId
                            }
                            lastChoicePayload = choice.payload
                            actionKinds[choice.kind] = (actionKinds[choice.kind] ?: 0) + 1
                            val result = service.step(
                                StepRequest(
                                    envId = envId!!,
                                    actionId = choice.actionId,
                                    action = choice.payload,
                                )
                            )
                            transitions++
                            game = observe(result)
                        }

                        is SemanticChoice.Structured -> {
                            val pending = game.pendingDecision
                                ?: error("Structured choice without a pending decision")
                            val decisionId = pending.decisionId
                                ?: error("Actor-facing structured decision has no decisionId")
                            val response = toDecisionResponse(decisionId, choice.selection)
                            lastActionKind = "DECISION"
                            val result = service.submitDecision(
                                envId = envId!!,
                                response = response,
                                actorId = game.agentToAct,
                            )
                            transitions++
                            game = observe(result)
                        }
                    }

                    assertDiagnosticsZero()?.let { failure ->
                        return EpisodeResult(
                            episode = episode,
                            transitions = transitions,
                            terminal = game.terminated,
                            truncated = game.truncated,
                            winner = game.winnerId,
                            actionKinds = actionKinds,
                            decisionFamilies = decisionFamilies,
                            commanderZoneDecisions = commanderZoneDecisions,
                            paymentDecisions = paymentDecisions,
                            searchDecisions = searchDecisions,
                            combatDecisions = combatDecisions,
                            failure = failure,
                        )
                    }
                    if (game.terminated || game.truncated) break
                }

                EpisodeResult(
                    episode = episode,
                    transitions = transitions,
                    terminal = game.terminated,
                    truncated = game.truncated,
                    winner = game.winnerId,
                    actionKinds = actionKinds,
                    decisionFamilies = decisionFamilies,
                    commanderZoneDecisions = commanderZoneDecisions,
                    paymentDecisions = paymentDecisions,
                    searchDecisions = searchDecisions,
                    combatDecisions = combatDecisions,
                    failure = null,
                )
            } catch (failure: CardDefinitionMissingException) {
                EpisodeResult(
                    episode = episode,
                    transitions = transitions,
                    terminal = false,
                    truncated = false,
                    winner = null,
                    failure = currentFailure(
                        classification = "A9_UNSUPPORTED_CARD",
                        code = failure.code,
                        reason = "Locked-card setup failed at the registry boundary",
                    ),
                )
            } catch (failure: UnsupportedPathFailure) {
                val signal = failure.diagnostics.firstOrNull()
                val diagnostic = diagnosticFailure()
                EpisodeResult(
                    episode = episode,
                    transitions = transitions,
                    terminal = false,
                    truncated = false,
                    winner = null,
                    failure = diagnostic ?: currentFailure(
                        classification = "A9_UNSUPPORTED_RULE_OR_MECHANIC",
                        code = signal?.semanticCode ?: "UNSUPPORTED_PATH_FAILURE",
                        reason = "Trusted execution raised an unsupported-path failure",
                    ),
                )
            } catch (failure: IllegalArgumentException) {
                EpisodeResult(
                    episode = episode,
                    transitions = transitions,
                    terminal = false,
                    truncated = false,
                    winner = null,
                    failure = currentFailure(
                        classification = "A5_CANDIDATE_CONTRACT_GAP",
                        code = "PUBLIC_CHOICE_REJECTED",
                        reason = failure.message
                            ?: "A choice generated from the published domain was rejected",
                        publicDomain = acceptancePublicActionDomain(
                            action = lastActionView,
                            payload = lastChoicePayload,
                            pendingDecision = observation?.pendingDecision,
                        ),
                        proposedFollowUp =
                            "Classify the public action contract and trusted execution rejection before changing #73",
                    ),
                )
            } catch (failure: IllegalStateException) {
                EpisodeResult(
                    episode = episode,
                    transitions = transitions,
                    terminal = false,
                    truncated = false,
                    winner = null,
                    failure = currentFailure(
                        classification = "A3_GYM_INTEGRATION_GAP",
                        code = "TRUSTED_STEP_REJECTED",
                        reason = "The trusted service rejected an otherwise typed corpus step",
                    ),
                )
            } finally {
                envId?.let { service.dispose(listOf(it)) }
            }
        }

        fun toDecisionResponse(
            decisionId: String,
            selection: SemanticDecision,
        ): DecisionResponse = when (selection) {
            is SemanticDecision.Targets -> TargetsResponse(decisionId, selection.selected)
            is SemanticDecision.Cards -> CardsSelectedResponse(decisionId, selection.selected)
            is SemanticDecision.Modes -> ModesChosenResponse(decisionId, selection.selected)
            is SemanticDecision.Color -> ColorChosenResponse(decisionId, selection.selected)
            is SemanticDecision.Number -> NumberChosenResponse(decisionId, selection.selected)
            is SemanticDecision.Distribution ->
                DistributionResponse(decisionId, selection.selected)
            is SemanticDecision.Ordered -> OrderedResponse(decisionId, selection.selected)
            is SemanticDecision.Piles -> PilesSplitResponse(decisionId, selection.selected)
            is SemanticDecision.Option -> OptionChosenResponse(decisionId, selection.selected)
            is SemanticDecision.Replacement ->
                ReplacementChosenResponse(decisionId, selection.from, selection.to)
            is SemanticDecision.Budget -> BudgetModalResponse(decisionId, selection.selected)
            is SemanticDecision.Damage -> CombatResolutionResponse(
                decisionId = decisionId,
                edges = selection.selected.map { DamageEdgeAmount(it.edgeId, it.amount) },
            )
        }

        fun corpusCases(): List<EpisodeConfig> {
            val primary = (0L..31L).flatMap { seed ->
                listOf(0, 1).map { startingPlayerIndex ->
                    EpisodeConfig(
                        seed = seed,
                        startingPlayerIndex = startingPlayerIndex,
                        seat0 = "Akiri",
                        seat1 = "Chevill",
                        rosterLabel = "Akiri-vs-Chevill",
                    )
                }
            }
            val rosterSwap = (0L..3L).flatMap { seed ->
                listOf(0, 1).map { startingPlayerIndex ->
                    EpisodeConfig(
                        seed = seed,
                        startingPlayerIndex = startingPlayerIndex,
                        seat0 = "Chevill",
                        seat1 = "Akiri",
                        rosterLabel = "Chevill-vs-Akiri",
                    )
                }
            }
            return primary + rosterSwap
        }

        fun policySeed(episode: EpisodeConfig): Long {
            val roster = if (episode.seat0 == "Akiri") 0x41L else 0x43L
            return episode.seed * 1_000_003L +
                episode.startingPlayerIndex * 97_409L +
                roster * 65_537L
        }

        fun EpisodeConfig.envConfig(): EnvConfig {
            val akiri = readLockedDeck("akiri-v0.1.txt")
            val chevill = readLockedDeck("chevill-v0.1.txt")
            val decks = mapOf(
                "Akiri" to akiri,
                "Chevill" to chevill,
            )
            fun player(name: String): PlayerSpec {
                val deck = decks.getValue(name)
                return PlayerSpec(
                    name = name,
                    deck = DeckSpec.Explicit(
                        deck.cards.drop(1).groupingBy { it }.eachCount(),
                    ),
                    startingLife = 40,
                    commanderCardName = deck.commander,
                )
            }
            return EnvConfig(
                players = listOf(player(seat0), player(seat1)),
                format = Format.Commander(),
                startingHandSize = 7,
                skipMulligans = true,
                useHandSmoother = false,
                startingPlayerIndex = startingPlayerIndex,
                seed = seed,
                maxSteps = MAX_STEPS,
                perspectivePlayerIndex = 0,
            )
        }

        fun exactPairRegistry(): CardRegistry = CardRegistry().apply {
            MtgSetCatalog.all.forEach { set ->
                register(set.cards)
                register(set.basicLands)
            }
        }

        fun readLockedDeck(fileName: String): LockedDeck {
            val path = lockedDeckPath(fileName)
            val cards = path.readLines()
                .filter { it.matches(Regex("^\\d{3}\\t.*")) }
                .map { it.substringAfterLast('\t') }
            return LockedDeck(
                commander = cards.first(),
                cards = cards,
            )
        }

        fun lockedDeckPath(fileName: String): Path =
            repositoryRoot().resolve("docs/ml/curriculum").resolve(fileName)

        fun repositoryRoot(): Path {
            val workingDirectory = Path.of(System.getProperty("user.dir"))
            return generateSequence(workingDirectory) { it.parent }
                .first { it.resolve("docs/ml/curriculum").toFile().isDirectory }
        }

        fun sha256(path: Path): String {
            val canonicalBytes = path.readText().replace("\r\n", "\n").toByteArray()
            return MessageDigest.getInstance("SHA-256")
                .digest(canonicalBytes)
                .joinToString("") { byte -> "%02X".format(byte) }
        }

        fun sha256Text(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02X".format(byte) }
    }
}

private fun sourceBoundCostPaymentAction(
    actionId: Int,
    source: EntityId,
    cost: JsonObject,
): LegalActionView = LegalActionView(
    actionId = actionId,
    kind = "ActivateAbility",
    description = "opaque source-bound cost-payment action",
    affordable = true,
    sourceEntityId = source,
    requiresStructuredAction = true,
    requiredPayloadFields = listOf("costPayment"),
    actionSemantics = buildJsonObject {
        put("type", "ActivateAbility")
        put("abilityKey", buildJsonObject {
            put("ability", buildJsonObject {
                put("cost", cost)
            })
        })
    },
)

private fun choiceBearingCostPaymentAction(
    actionId: Int,
    source: EntityId,
    targets: List<EntityId>,
    count: Int,
    min: Int,
    max: Int,
): LegalActionView = LegalActionView(
    actionId = actionId,
    kind = "ActivateAbility",
    description = "opaque choice-bearing cost-payment action",
    affordable = true,
    sourceEntityId = source,
    validSacrificeTargets = targets,
    sacrificeCount = count,
    sacrificeMinCount = min,
    sacrificeMaxCount = max,
    requiresStructuredAction = true,
    requiredPayloadFields = listOf("costPayment"),
    actionSemantics = buildJsonObject {
        put("type", "ActivateAbility")
        put("abilityKey", buildJsonObject {
            put("ability", buildJsonObject {
                put("cost", buildJsonObject {
                    put("type", "CostAtomWrapper")
                    put("atom", buildJsonObject {
                        put("type", "AtomSacrifice")
                        put("count", count)
                    })
                })
            })
        })
    },
)

private fun attackPolicyAction(
    domain: AttackDeclarationDomainV1?,
    targetEntityIds: List<EntityId> = emptyList(),
): LegalActionView = LegalActionView(
    actionId = 201,
    kind = "DeclareAttackers",
    description = "public attack declaration",
    affordable = true,
    targetEntityIds = targetEntityIds,
    attackDeclarationDomain = domain,
    requiresStructuredAction = true,
    requiredPayloadFields = listOf("attackers", "bands"),
    actionSemantics = buildJsonObject {
        put("type", "DeclareAttackers")
        put("attackers", buildJsonObject {})
        put("bands", JsonArray(emptyList()))
    },
)

private fun attackPolicyDomain(
    attackerToDefenders: Map<EntityId, List<EntityId>>,
    mandatoryAttackers: List<EntityId> = emptyList(),
    canDeclareZeroAttackers: Boolean = false,
    maxAttackers: Int? = null,
    coAttackerRequirements: Map<EntityId, List<AttackCoAttackerRequirementV1>> = emptyMap(),
): AttackDeclarationDomainV1 {
    val nonBandingAttackersByDefender = attackerToDefenders.entries
        .flatMap { (attacker, defenders) ->
            defenders.map { defender -> defender to attacker }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, attackers) -> attackers.sortedBy(EntityId::value) }

    return AttackDeclarationDomainV1(
        attackerToDefenders = attackerToDefenders,
        mandatoryAttackers = mandatoryAttackers,
        canDeclareZeroAttackers = canDeclareZeroAttackers,
        maxAttackers = maxAttackers,
        coAttackerRequirements = coAttackerRequirements,
        bandConstraints = AttackBandConstraintsV1(
            bandingAttackersByDefender = emptyMap(),
            nonBandingAttackersByDefender = nonBandingAttackersByDefender,
        ),
    )
}

private fun publicTargetRequirement(
    index: Int,
    candidates: List<EntityId>,
    minTargets: Int = 1,
    maxTargets: Int = minTargets,
): TargetRequirementDomain = TargetRequirementDomain(
    index = index,
    description = "public target slot $index",
    minTargets = minTargets,
    maxTargets = maxTargets,
    candidates = candidates,
    targetZone = null,
    mustDifferFromEarlier = false,
    sameController = false,
    sameOwner = false,
    sameCreatureType = false,
    sameCardType = false,
    totalManaValueAtMost = null,
    differentNames = false,
    xConstrainsManaValue = false,
    xConstrainsManaValueExactly = false,
    xConstrainsPower = false,
    xConstrainsCount = false,
)

private fun policyPayload(choice: SemanticChoice): JsonObject {
    check(choice is SemanticChoice.Action) { "Expected an attack action, got $choice" }
    return checkNotNull(choice.payload) { "Attack action omitted its structured payload" }
}

private fun acceptancePublicActionDomain(
    action: LegalActionView?,
    payload: JsonObject?,
    pendingDecision: com.wingedsheep.gym.contract.PendingDecisionView?,
): String {
    if (action == null) {
        return "action=not captured; pendingDecision=$pendingDecision; payload=$payload"
    }
    return listOf(
        "kind=${action.kind}",
        "requiredPayloadFields=${action.requiredPayloadFields}",
        "sourceEntityId=${action.sourceEntityId}",
        "targetEntityIds=${action.targetEntityIds}",
        "targetDomain=${action.targetDomain}",
        "attackDeclarationDomain=${action.attackDeclarationDomain}",
        "paymentDomain=${action.paymentDomain}",
        "minTargets=${action.minTargets}",
        "maxTargets=${action.maxTargets}",
        "validSacrificeTargets=${action.validSacrificeTargets}",
        "sacrificeCount=${action.sacrificeCount}",
        "sacrificeMinCount=${action.sacrificeMinCount}",
        "sacrificeMaxCount=${action.sacrificeMaxCount}",
        "actionSemantics=${action.actionSemantics}",
        "pendingDecision=$pendingDecision",
        "submittedPayload=$payload",
    ).joinToString("; ")
}

private fun publicActionObservation(
    action: LegalActionView,
    player: EntityId = EntityId("player-0"),
): TrainingObservation = TrainingObservation(
    schemaHash = "test-schema",
    perspectivePlayerId = player,
    agentToAct = player,
    turnNumber = 1,
    phase = com.wingedsheep.sdk.core.Phase.PRECOMBAT_MAIN,
    step = com.wingedsheep.sdk.core.Step.PRECOMBAT_MAIN,
    activePlayerId = player,
    priorityPlayerId = player,
    players = emptyList(),
    zones = emptyList(),
    stack = emptyList(),
    pendingDecision = null,
    legalActions = listOf(action),
    terminated = false,
    truncated = false,
    winnerId = null,
    stateDigest = "digest",
)

private fun decodeCostPayment(choice: SemanticChoice): AdditionalCostPayment {
    check(choice is SemanticChoice.Action) { "Expected a cost-payment action, got $choice" }
    return Json {
        ignoreUnknownKeys = true
    }.decodeFromJsonElement(
        AdditionalCostPayment.serializer(),
        choice.payload?.get("costPayment")
            ?: error("Cost-payment action omitted costPayment"),
    )
}

private data class LockedDeck(
    val commander: String,
    val cards: List<String>,
)

private data class ReplayFrame(
    val semanticObservation: String,
    val stateDigest: String,
    val terminated: Boolean,
    val truncated: Boolean,
)

private sealed interface ReplayDecision {
    data class Action(
        val kind: String,
        val semanticKey: String,
        val semanticOrdinal: Int,
        val payload: JsonObject?,
    ) : ReplayDecision

    data class Structured(
        val family: String,
        val selection: SemanticDecision,
    ) : ReplayDecision
}

private data class ReplayTrace(
    val episode: EpisodeConfig,
    val frames: List<ReplayFrame>,
    val decisions: List<ReplayDecision>,
    val actions: List<GameAction>,
    val checkpoints: List<ReplayCheckpointData>,
    val gameConfig: GameConfig,
    val playerIds: List<EntityId>,
    val requiredPayloadFields: Set<String>,
)

private data class ReplayGateEvidence(
    val traces: List<ReplayTrace>,
    val authoritative: List<AuthoritativeReplayCase>,
    val failures: List<String>,
) {
    fun render(): String = buildString {
        appendLine("EXACT_PAIR_REPLAY_GATE")
        appendLine(
            "cases=${traces.size}; authoritativeCompactReplay=${authoritative.size}; " +
                "failures=${failures.size}",
        )
        traces.forEach { trace ->
            appendLine(
                "case=${trace.episode.rosterLabel}/seed=${trace.episode.seed}/" +
                    "starting=${trace.episode.startingPlayerIndex}; " +
                    "frames=${trace.frames.size}; decisions=${trace.decisions.size}; " +
                    "terminal=${trace.frames.last().terminated}; " +
                "truncated=${trace.frames.last().truncated}",
            )
        }
        authoritative.forEach { replay ->
            appendLine(
                "compactReplay=${replay.caseLabel}; codecRoundTrip=${replay.codecRoundTrip}; " +
                    "fidelity=${replay.fidelity}; frames=${replay.frameCount}; " +
                    "checkpoints=${replay.checkpointCount}",
            )
        }
        failures.forEach { appendLine("failure=$it") }
    }
}

private data class AuthoritativeReplayCase(
    val caseLabel: String,
    val codecRoundTrip: Boolean,
    val fidelity: String,
    val frameCount: Int,
    val checkpointCount: Int,
)

private data class ReplayCheckpointData(
    val afterActionCount: Int,
    val fingerprint: String,
)

/**
 * Test-only bridge to the replay module.  :gym intentionally does not depend on :game-server, so
 * this loads the already-built game-server replay classes instead of copying their codec or
 * verifier into the acceptance harness.  A clean checkout compiles that existing main output on
 * demand; no source or build-file change is made by the gate.
 */
private object CompactReplayBridge {
    private const val COMPACT_REPLAY = "com.wingedsheep.gameserver.replay.CompactReplay"
    private const val REPLAY_CODEC = "com.wingedsheep.gameserver.replay.ReplayCodec"
    private const val REPLAY_FINGERPRINT = "com.wingedsheep.gameserver.replay.ReplayFingerprint"
    private const val REPLAY_RECONSTRUCTOR = "com.wingedsheep.gameserver.replay.ReplayReconstructor"
    private const val REPLAY_SETUP = "com.wingedsheep.gameserver.replay.ReplaySetup"
    private const val REPLAY_PLAYER_SETUP = "com.wingedsheep.gameserver.replay.ReplayPlayerSetup"
    private const val REPLAY_PLAYER_INFO = "com.wingedsheep.gameserver.replay.ReplayPlayerInfo"
    private const val REPLAY_CHECKPOINT = "com.wingedsheep.gameserver.replay.ReplayCheckpoint"
    private const val SEAT_INFO =
        "com.wingedsheep.gameserver.protocol.ServerMessage\$PlayerSeatInfo"

    @Volatile
    private var cachedRuntime: ReplayRuntime? = null

    fun fingerprint(state: com.wingedsheep.engine.state.GameState): String =
        runtime(repositoryRoot = findRepositoryRoot()).fingerprint(state)

    fun checkpointCadence(repositoryRoot: Path): Int =
        runtime(repositoryRoot).checkpointCadence()

    fun verify(
        trace: ReplayTrace,
        registry: CardRegistry,
        repositoryRoot: Path,
    ): AuthoritativeReplayCase {
        val replayRuntime = runtime(repositoryRoot)
        val replay = replayRuntime.compactReplay(trace)
        val codec = replayRuntime.singleton(REPLAY_CODEC)
        val encoded = replayRuntime.invoke(codec, "encode", replay) as String
        val decoded = replayRuntime.invoke(codec, "decode", encoded)
        check(decoded == replay) {
            "CompactReplay codec round-trip changed the captured replay for ${trace.episode}"
        }

        val reconstructor = replayRuntime.newInstance(
            REPLAY_RECONSTRUCTOR,
            arrayOf(
                CardRegistry::class.java,
                com.wingedsheep.engine.registry.PrintingRegistry::class.java,
                com.wingedsheep.engine.registry.TokenArtRegistry::class.java,
            ),
            arrayOf(registry, null, null),
        )
        val reconstructed = replayRuntime.invoke(reconstructor, "reconstruct", decoded)
        val replayResult = checkNotNull(reconstructed)
        val fidelity = checkNotNull(replayRuntime.invoke(replayResult, "getFidelity")).toString()
        val frameCount = checkNotNull(replayRuntime.invoke(replayResult, "getFrameCount")) as Int
        check(fidelity == "EXACT") {
            "CompactReplay reconstruction was $fidelity for ${trace.episode}: " +
                replayRuntime.invoke(replayResult, "getDivergenceReason")
        }
        check(frameCount == trace.frames.size) {
            "CompactReplay reconstructed $frameCount frames; captured ${trace.frames.size}"
        }

        return AuthoritativeReplayCase(
            caseLabel = "${trace.episode.rosterLabel}/seed=${trace.episode.seed}/" +
                "starting=${trace.episode.startingPlayerIndex}",
            codecRoundTrip = true,
            fidelity = fidelity,
            frameCount = frameCount,
            checkpointCount = trace.checkpoints.size,
        )
    }

    private fun runtime(repositoryRoot: Path): ReplayRuntime {
        cachedRuntime?.let { return it }
        synchronized(this) {
            cachedRuntime?.let { return it }
            val parent = Thread.currentThread().contextClassLoader
                ?: CompactReplayBridge::class.java.classLoader
            val loadedByParent = runCatching {
                Class.forName(COMPACT_REPLAY, false, parent)
            }.isSuccess
            val replayRuntime = if (loadedByParent) {
                ReplayRuntime(parent)
            } else {
                ReplayRuntime(replayClassLoader(repositoryRoot, parent))
            }
            replayRuntime.loadClass(COMPACT_REPLAY)
            cachedRuntime = replayRuntime
            return replayRuntime
        }
    }

    private fun replayClassLoader(repositoryRoot: Path, parent: ClassLoader): ClassLoader {
        val kotlinClasses = repositoryRoot.resolve("game-server/build/classes/kotlin/main")
        val replayClass = kotlinClasses.resolve(
            "com/wingedsheep/gameserver/replay/CompactReplay.class",
        )
        if (!Files.exists(replayClass)) {
            compileGameServer(repositoryRoot)
        }
        check(Files.exists(replayClass)) {
            "Existing game-server replay classes are unavailable after compilation"
        }
        val urls = listOf(
            kotlinClasses,
            repositoryRoot.resolve("game-server/build/classes/java/main"),
            repositoryRoot.resolve("game-server/build/resources/main"),
        ).filter(Files::exists)
            .map { it.toUri().toURL() }
        return URLClassLoader(urls.toTypedArray(), parent)
    }

    private fun compileGameServer(repositoryRoot: Path) {
        val windows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
        val wrapper = repositoryRoot.resolve(if (windows) "gradlew.bat" else "gradlew")
        check(Files.exists(wrapper)) { "Gradle wrapper not found at $wrapper" }
        val command = if (windows) {
            listOf(
                "cmd.exe",
                "/d",
                "/c",
                "\"$wrapper\" :game-server:compileKotlin --no-daemon --console=plain",
            )
        } else {
            listOf(
                wrapper.toString(),
                ":game-server:compileKotlin",
                "--no-daemon",
                "--console=plain",
            )
        }
        val process = ProcessBuilder(command)
            .directory(repositoryRoot.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        check(process.waitFor(15, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            "Compiling existing game-server replay classes timed out"
        }
        check(process.exitValue() == 0) {
            "Compiling existing game-server replay classes failed with exit=${process.exitValue()}"
        }
    }

    private fun findRepositoryRoot(): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir"))
        return generateSequence(workingDirectory) { it.parent }
            .first { it.resolve("docs/ml/curriculum").toFile().isDirectory }
    }

    private class ReplayRuntime(private val loader: ClassLoader) {
        fun loadClass(name: String): Class<*> = loader.loadClass(name)

        fun singleton(name: String): Any =
            loadClass(name).getField("INSTANCE").get(null)

        fun invoke(target: Any, name: String, vararg arguments: Any?): Any? {
            val method = target.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == arguments.size
            } ?: error("No $name/${arguments.size} method on ${target.javaClass.name}")
            return try {
                method.invoke(target, *arguments)
            } catch (failure: java.lang.reflect.InvocationTargetException) {
                throw (failure.targetException as? Exception ?: failure)
            }
        }

        fun newInstance(
            name: String,
            parameterTypes: Array<Class<*>>,
            arguments: Array<Any?>,
        ): Any = try {
            loadClass(name).getConstructor(*parameterTypes).newInstance(*arguments)
        } catch (failure: java.lang.reflect.InvocationTargetException) {
            throw (failure.targetException as? Exception ?: failure)
        }

        fun fingerprint(state: com.wingedsheep.engine.state.GameState): String {
            val fingerprint = singleton(REPLAY_FINGERPRINT)
            return invoke(
                fingerprint,
                "of",
                state,
            ) as String
        }

        fun checkpointCadence(): Int {
            val policy = loadClass(
                "com.wingedsheep.gameserver.replay.ReplayRecordingPolicy",
            )
            return policy.getField("CHECKPOINT_EVERY_ACTIONS").getInt(null)
        }

        fun compactReplay(trace: ReplayTrace): Any {
            val config = trace.gameConfig
            val playerSetups = trace.playerIds.zip(config.players).map { (playerId, player) ->
                newInstance(
                    REPLAY_PLAYER_SETUP,
                    arrayOf(
                        String::class.java,
                        String::class.java,
                        com.wingedsheep.sdk.model.Deck::class.java,
                        Int::class.javaPrimitiveType!!,
                        String::class.java,
                    ),
                    arrayOf(
                        playerId.value,
                        player.name,
                        player.deck,
                        player.startingLife,
                        player.commanderCardName,
                    ),
                )
            }
            val playerInfos = trace.playerIds.zip(config.players).map { (playerId, player) ->
                newInstance(
                    REPLAY_PLAYER_INFO,
                    arrayOf(String::class.java, String::class.java),
                    arrayOf(playerId.value, player.name),
                )
            }
            val seatInfos = trace.playerIds.zip(config.players).mapIndexed { index, (playerId, player) ->
                newInstance(
                    SEAT_INFO,
                    arrayOf(
                        String::class.java,
                        String::class.java,
                        Int::class.javaPrimitiveType!!,
                        Boolean::class.javaPrimitiveType!!,
                        Boolean::class.javaPrimitiveType!!,
                        Integer::class.java,
                        Boolean::class.javaPrimitiveType!!,
                    ),
                    arrayOf(playerId.value, player.name, index, false, false, null, false),
                )
            }
            val setup = newInstance(
                REPLAY_SETUP,
                arrayOf(
                    Long::class.javaPrimitiveType!!,
                    com.wingedsheep.sdk.core.Format::class.java,
                    com.wingedsheep.sdk.core.AttackMode::class.java,
                    Int::class.javaPrimitiveType!!,
                    Boolean::class.javaPrimitiveType!!,
                    Boolean::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    Integer::class.java,
                    List::class.java,
                    List::class.java,
                    List::class.java,
                ),
                arrayOf(
                    config.seed!!,
                    config.format,
                    config.attackMode,
                    config.startingHandSize,
                    config.skipMulligans,
                    config.useHandSmoother,
                    config.handSmootherCandidates,
                    config.startingPlayerIndex,
                    config.teams,
                    playerSetups,
                    seatInfos,
                ),
            )
            val checkpoints = trace.checkpoints.map { checkpoint ->
                newInstance(
                    REPLAY_CHECKPOINT,
                    arrayOf(Int::class.javaPrimitiveType!!, String::class.java),
                    arrayOf(checkpoint.afterActionCount, checkpoint.fingerprint),
                )
            }
            return newInstance(
                COMPACT_REPLAY,
                arrayOf(
                    Int::class.javaPrimitiveType!!,
                    String::class.java,
                    List::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    Integer::class.java,
                    setup.javaClass,
                    List::class.java,
                    List::class.java,
                    String::class.java,
                    List::class.java,
                    List::class.java,
                ),
                arrayOf(
                    4,
                    "a5-exact-pair-${trace.episode.rosterLabel}-${trace.episode.seed}-" +
                        trace.episode.startingPlayerIndex,
                    playerInfos,
                    "2026-01-01T00:00:00Z",
                    "2026-01-01T00:00:00Z",
                    null,
                    null,
                    null,
                    setup,
                    trace.actions,
                    emptyList<Any>(),
                    "test",
                    emptyList<String>(),
                    checkpoints,
                ),
            )
        }
    }
}

private data class PrivacyGateEvidence(
    val perspectives: Set<String>,
    val observations: Int,
    val failures: List<String>,
) {
    fun render(): String =
        "PRIVACY_FINAL_GATE\nperspectives=$perspectives; observations=$observations; " +
            "failures=${failures.size}\n" + failures.joinToString("\n") { "failure=$it" }
}

private data class ClosureRow(
    val family: String,
    val observedCount: Int,
    val disposition: String,
)

private data class DecisionClosureEvidence(
    val rows: List<ClosureRow>,
    val resolvedCards: Int,
    val uncovered: List<String>,
    val sourceBoundary: List<String>,
) {
    fun render(): String = buildString {
        appendLine("DECISION_CLOSURE_GATE")
        appendLine("resolvedCards=$resolvedCards; uncovered=$uncovered")
        rows.forEach { row ->
            appendLine("${row.family}: ${row.disposition} (observed=${row.observedCount})")
        }
        sourceBoundary.forEach { appendLine(it) }
    }
}

private enum class Issue56Result {
    PROVEN_UNREACHABLE_EXACT_PAIR,
    REACHABLE_BLOCKER,
}

private data class Issue56CopySite(
    val cardName: String,
    val type: String,
    val targetRequirementCount: Int?,
    val path: String,
) {
    override fun toString(): String =
        "$cardName:$type:targetRequirements=${targetRequirementCount ?: "UNKNOWN"}@$path"
}

private data class Issue56Evidence(
    val result: Issue56Result,
    val resolvedCards: Int,
    val retargetingSites: List<Issue56CopySite>,
    val evidence: List<String>,
) {
    fun render(): String = buildString {
        appendLine("ISSUE_56_GATE: $result")
        appendLine("resolvedCards=$resolvedCards; retargetingSites=$retargetingSites")
        evidence.forEach { appendLine(it) }
    }
}

private val PUBLIC_ACTION_DOMAIN_FAMILIES = setOf(
    "ActivateAbility",
    "CastSpell",
    "CastSpellMode",
    "CastWithKicker",
    "CycleCard",
    "DECISION",
    "DeclareAttackers",
    "PassPriority",
    "PlayLand",
    "PRIORITY",
)

private val STRUCTURED_DECISION_DOMAIN_FAMILIES = setOf(
    "CHOOSE_COLOR",
    "CHOOSE_TARGETS",
    "SELECT_CARDS",
    "YES_NO",
)

private data class EpisodeConfig(
    val seed: Long,
    val startingPlayerIndex: Int,
    val seat0: String,
    val seat1: String,
    val rosterLabel: String,
)

private data class ExactPairCorpusReachabilitySnapshot(
    val actionKinds: Map<String, Int>,
    val decisionFamilies: Map<String, Int>,
    val requiredPayloadFields: Set<String>,
    val totalTransitions: Int,
    val definitionDigest: String,
)

private data class DefinitionScanEvidence(
    val cardCount: Int,
    val emptySerializations: List<String>,
    val digest: String,
)

private data class AcceptanceFailure(
    val classification: String,
    val code: String,
    val reason: String,
    val diagnostic: String,
    val publicDomain: String,
    val proposedFollowUp: String,
    val seed: Long,
    val policySeed: Long,
    val roster: String,
    val startingPlayerIndex: Int,
    val step: Int,
    val actor: String?,
    val stateDigest: String?,
    val decisionFamily: String,
    val actionKind: String,
) {
    override fun toString(): String = listOf(
        "CLASSIFICATION: $classification",
        "SEED: $seed",
        "POLICY_SEED: $policySeed",
        "ROSTER: $roster",
        "STARTING_PLAYER: $startingPlayerIndex",
        "EXTERNAL_STEP: $step",
        "ACTOR: ${actor ?: "null"}",
        "STATE_DIGEST: ${stateDigest ?: "null"}",
        "ACTION_KIND: $actionKind",
        "LAST_DECISION_FAMILY: $decisionFamily",
        "DIAGNOSTIC: $diagnostic (code=$code)",
        "PUBLIC_DOMAIN: $publicDomain",
        "ROOT_CAUSE: $reason",
        "PROPOSED_FOLLOW_UP: $proposedFollowUp",
    ).joinToString("\n")
}

private data class EpisodeResult(
    val episode: EpisodeConfig,
    val transitions: Int,
    val terminal: Boolean,
    val truncated: Boolean,
    val winner: EntityId?,
    val actionKinds: Map<String, Int> = emptyMap(),
    val decisionFamilies: Map<String, Int> = emptyMap(),
    val commanderZoneDecisions: Int = 0,
    val paymentDecisions: Int = 0,
    val searchDecisions: Int = 0,
    val combatDecisions: Int = 0,
    val failure: AcceptanceFailure?,
)

private class CorpusEvidence {
    var episodesStarted: Int = 0
        private set
    var terminalEpisodes: Int = 0
        private set
    var truncatedEpisodes: Int = 0
        private set
    var totalExternalTransitions: Int = 0
        private set
    var firstFailure: AcceptanceFailure? = null
        private set
    val episodeTransitions = mutableListOf<Int>()
    private val actionKinds = TreeMap<String, Int>()
    private val decisionFamilies = TreeMap<String, Int>()
    private val diagnosticKinds = TreeMap<String, Int>()
    private val diagnosticCodes = TreeMap<String, Int>()
    var commanderZoneDecisions: Int = 0
        private set
    var paymentDecisions: Int = 0
        private set
    var searchDecisions: Int = 0
        private set
    var combatDecisions: Int = 0
        private set

    fun record(result: EpisodeResult) {
        episodesStarted++
        totalExternalTransitions += result.transitions
        episodeTransitions += result.transitions
        if (result.terminal) terminalEpisodes++
        if (result.truncated) truncatedEpisodes++
        commanderZoneDecisions += result.commanderZoneDecisions
        paymentDecisions += result.paymentDecisions
        searchDecisions += result.searchDecisions
        combatDecisions += result.combatDecisions
        result.actionKinds.forEach { (key, value) -> actionKinds[key] = (actionKinds[key] ?: 0) + value }
        result.decisionFamilies.forEach { (key, value) ->
            decisionFamilies[key] = (decisionFamilies[key] ?: 0) + value
        }
        result.failure?.let { failure ->
            if (firstFailure == null) firstFailure = failure
            diagnosticCodes[failure.code] = (diagnosticCodes[failure.code] ?: 0) + 1
            val kind = when (failure.classification) {
                "A9_UNSUPPORTED_CARD" -> "UNSUPPORTED_CARD"
                "A9_UNSUPPORTED_DECISION" -> "UNSUPPORTED_DECISION"
                "A9_UNSUPPORTED_RULE_OR_MECHANIC" -> "UNSUPPORTED_RULE_OR_MECHANIC"
                "A5_NATIVE_POLICY_FALLBACK" -> "NATIVE_POLICY_FALLBACK"
                else -> null
            }
            kind?.let { diagnosticKinds[it] = (diagnosticKinds[it] ?: 0) + 1 }
        }
    }

    fun render(): String = buildString {
        appendLine("ENVIRONMENT_V1_CORPUS")
        appendLine("targetEpisodes=72")
        appendLine("episodesStarted=" + episodesStarted)
        appendLine("terminalEpisodes=" + terminalEpisodes)
        appendLine("truncatedEpisodes=" + truncatedEpisodes)
        appendLine("totalExternalTransitions=" + totalExternalTransitions)
        appendLine("maxEpisodeTransitions=" + (episodeTransitions.maxOrNull() ?: 0))
        appendLine("commanderZoneDecisions=" + commanderZoneDecisions)
        appendLine("paymentDecisions=" + paymentDecisions)
        appendLine("searchDecisions=" + searchDecisions)
        appendLine("combatDecisions=" + combatDecisions)
        appendLine("actionKinds=" + actionKinds)
        appendLine("decisionFamilies=" + decisionFamilies)
        val kinds = listOf(
            "UNSUPPORTED_CARD",
            "UNSUPPORTED_DECISION",
            "UNSUPPORTED_RULE_OR_MECHANIC",
            "NATIVE_POLICY_FALLBACK",
        ).associateWith { diagnosticKinds[it] ?: 0 }
        val codes = linkedMapOf(
            "CARD_DEFINITION_MISSING" to (diagnosticCodes["CARD_DEFINITION_MISSING"] ?: 0),
            "STRUCTURED_DECISION_DOMAIN_MISSING" to
                (diagnosticCodes["STRUCTURED_DECISION_DOMAIN_MISSING"] ?: 0),
            "CHAIN_COPY_COST_UNSUPPORTED" to
                (diagnosticCodes["CHAIN_COPY_COST_UNSUPPORTED"] ?: 0),
            "ANY_PLAYER_MAY_PAY_COST_UNSUPPORTED" to
                (diagnosticCodes["ANY_PLAYER_MAY_PAY_COST_UNSUPPORTED"] ?: 0),
            "PAYMENT_DOMAIN_UNSUPPORTED" to
                (diagnosticCodes["PAYMENT_DOMAIN_UNSUPPORTED"] ?: 0),
            "SACRIFICE_AND_PAY_COST_UNSUPPORTED" to
                (diagnosticCodes["SACRIFICE_AND_PAY_COST_UNSUPPORTED"] ?: 0),
            "PREVENT_DAMAGE_CONFIGURATION_UNSUPPORTED" to
                (diagnosticCodes["PREVENT_DAMAGE_CONFIGURATION_UNSUPPORTED"] ?: 0),
            "LIBRARY_DESTINATION_UNSUPPORTED" to
                (diagnosticCodes["LIBRARY_DESTINATION_UNSUPPORTED"] ?: 0),
            "ACTIVATED_ABILITY_SHAPE_UNSUPPORTED" to
                (diagnosticCodes["ACTIVATED_ABILITY_SHAPE_UNSUPPORTED"] ?: 0),
            "SKIP_NEXT_DRAW_TARGET_UNSUPPORTED" to
                (diagnosticCodes["SKIP_NEXT_DRAW_TARGET_UNSUPPORTED"] ?: 0),
            "TRUSTED_NATIVE_POLICY_FALLBACK" to
                (diagnosticCodes["TRUSTED_NATIVE_POLICY_FALLBACK"] ?: 0),
        )
        diagnosticCodes.filterKeys { it !in codes }.forEach { (code, count) ->
            codes[code] = count
        }
        appendLine("diagnosticCountsByKind=" + kinds)
        appendLine("diagnosticCountsByCode=" + codes)
        appendLine("firstFailure=" + (firstFailure ?: "none"))
    }
}
