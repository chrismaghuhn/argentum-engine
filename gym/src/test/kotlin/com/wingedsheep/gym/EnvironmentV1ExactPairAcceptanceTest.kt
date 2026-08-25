package com.wingedsheep.gym

import com.wingedsheep.engine.core.BudgetModalResponse
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DamageEdgeAmount
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.DiagnosticKind
import com.wingedsheep.engine.core.DistributionResponse
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.engine.core.PilesSplitResponse
import com.wingedsheep.engine.core.ReplacementChosenResponse
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.core.UnsupportedPathFailure
import com.wingedsheep.engine.registry.CardDefinitionMissingException
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.contract.ObservationResult
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
import com.wingedsheep.gym.contract.TargetRequirementDomain
import com.wingedsheep.gym.contract.ZoneView
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
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.security.MessageDigest
import java.util.TreeMap
import kotlin.io.path.readBytes
import kotlin.io.path.readLines
import kotlin.io.path.readText

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

    test("runs the exact 72-episode trusted corpus with first-gap stop semantics") {
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
}) {
    private companion object {
        const val AKIRI_SHA256 =
            "0C5878E3B393A2CB6317FBE64E0827E4E9A562A0346E5A75820F11081F0909C6"
        const val CHEVILL_SHA256 =
            "D158760D404F32C32110C377B1CA6E3EF9406FD6E0CC29B620CB5BCF573AC8B2"
        const val MAX_STEPS = 2_000

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

private data class EpisodeConfig(
    val seed: Long,
    val startingPlayerIndex: Int,
    val seat0: String,
    val seat1: String,
    val rosterLabel: String,
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
