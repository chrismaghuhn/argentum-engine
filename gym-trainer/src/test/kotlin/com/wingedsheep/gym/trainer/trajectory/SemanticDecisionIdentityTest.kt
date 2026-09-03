package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.contract.CompleteLegalDomainKind
import com.wingedsheep.gym.contract.CompleteLegalDomainV1
import com.wingedsheep.gym.contract.DecisionShape
import com.wingedsheep.gym.contract.ModeOptionDomain
import com.wingedsheep.gym.contract.ManaSourceDomain
import com.wingedsheep.gym.contract.ManaSourcesDomain
import com.wingedsheep.gym.contract.PendingDecisionKind
import com.wingedsheep.gym.contract.PendingDecisionView
import com.wingedsheep.gym.contract.PlayerObservationV1
import com.wingedsheep.gym.contract.StateDigest
import com.wingedsheep.gym.contract.TargetRequirementDomain
import com.wingedsheep.gym.contract.TargetsDomain
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private const val FIXTURE_SEED = 70L
private val SEMANTIC_EPISODE = "a".repeat(64)
private val OBSERVATION_DIGEST = "b".repeat(64)

class SemanticDecisionIdentityTest : FunSpec({

    fun registry(): CardRegistry = CardRegistry().also {
        it.register(PortalSet.cards)
        it.register(PortalSet.basicLands)
    }

    fun environment(): GameEnvironment {
        val environment = GameEnvironment.create(registry())
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 20)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 2,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = FIXTURE_SEED,
            )
        )
        return environment
    }

    fun observation(environment: GameEnvironment): TrainingObservation =
        com.wingedsheep.gym.contract.ObservationBuilder(cardRegistry = registry()).build(
            environment.state,
            environment.playerIds.first(),
            environment.legalActions(),
        ).observation as TrainingObservation

    fun foldedObservation(environment: GameEnvironment, decisionId: String): TrainingObservation {
        val player = environment.playerIds.first()
        val pending = PendingDecisionView(
            decisionId = decisionId,
            kind = PendingDecisionKind.YES_NO,
            playerId = player,
            prompt = "presentation",
            requiresStructuredResponse = false,
            shape = DecisionShape(),
        )
        val source = observation(environment)
        return source.copy(
            pendingDecision = pending,
            legalActions = listOf(
                com.wingedsheep.gym.contract.LegalActionView(
                    actionId = 0,
                    kind = "DECISION",
                    description = "Yes",
                    affordable = true,
                    actionSemantics = buildJsonObject {
                        put("type", "YesNoResponse")
                        put("choice", true)
                    },
                    isDecisionOption = true,
                ),
                com.wingedsheep.gym.contract.LegalActionView(
                    actionId = 1,
                    kind = "DECISION",
                    description = "No",
                    affordable = true,
                    actionSemantics = buildJsonObject {
                        put("type", "YesNoResponse")
                        put("choice", false)
                    },
                    isDecisionOption = true,
                ),
            ),
        )
    }

    fun identity(
        prefix: SemanticReplayPrefixV1,
        replayActionIndex: Int = prefix.inputs.size,
        perspectivePlayerId: String = "player-a",
        decisionKind: String = "PRIORITY",
        observationDigest: String = OBSERVATION_DIGEST,
        candidateDomainDigest: String = "c".repeat(64),
    ): SemanticDecisionIdentityV1 = SemanticDecisionIdentityV1(
        semanticEpisodeId = SEMANTIC_EPISODE,
        replayPrefixDigest = prefix.digest().value,
        replayActionIndex = replayActionIndex,
        perspectivePlayerId = perspectivePlayerId,
        decisionKind = decisionKind,
        observationDigest = observationDigest,
        candidateDomainDigest = candidateDomainDigest,
    )

    fun replaceJsonValue(objectValue: JsonObject, key: String, value: kotlinx.serialization.json.JsonElement) =
        buildJsonObject {
            objectValue.forEach { (existingKey, existingValue) ->
                put(existingKey, if (existingKey == key) value else existingValue)
            }
        }

    fun actionDomain(environment: GameEnvironment): CompleteLegalDomainV1 =
        CompleteLegalDomainV1.from(observation(environment))

    fun candidateWithRequiredPayload(
        domain: CompleteLegalDomainV1,
        fields: List<String>,
    ): JsonObject {
        val candidate = domain.candidates.first()
        return replaceJsonValue(
            replaceJsonValue(candidate, "requiredPayloadFields", buildJsonArray {
                fields.forEach { add(JsonPrimitive(it)) }
            }),
            "requiresStructuredAction",
            JsonPrimitive(true),
        )
    }

    fun targetDomain(): TargetsDomain = TargetsDomain(
        requirements = listOf(
            TargetRequirementDomain(
                index = 0,
                description = "presentation",
                minTargets = 1,
                maxTargets = 1,
                candidates = listOf(EntityId("target")),
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
        ),
        canCancel = false,
    )

    test("fresh action IDs do not change the semantic replay prefix or decision ID") {
        val environment = environment()
        val first = observation(environment)
        val second = first.copy(
            legalActions = first.legalActions.mapIndexed { index, action ->
                action.copy(actionId = 1000 + index)
            }
        )
        val firstDomain = CompleteLegalDomainV1.from(first)
        val secondDomain = CompleteLegalDomainV1.from(second)
        val firstChosen = ChosenSemanticActionV1.from(firstDomain, firstDomain.candidates.first())
        val secondChosen = ChosenSemanticActionV1.from(secondDomain, secondDomain.candidates.first())
        val firstPrefix = SemanticReplayPrefixV1(
            inputs = listOf(SemanticReplayInputV1.action(firstChosen))
        )
        val secondPrefix = SemanticReplayPrefixV1(
            inputs = listOf(SemanticReplayInputV1.action(secondChosen))
        )

        firstPrefix.digest() shouldBe secondPrefix.digest()
        identity(firstPrefix) shouldBe identity(secondPrefix)
    }

    test("fresh decision IDs do not change a chosen folded response") {
        val environment = environment()
        val firstDomain = CompleteLegalDomainV1.from(foldedObservation(environment, "decision-a"))
        val secondDomain = CompleteLegalDomainV1.from(foldedObservation(environment, "decision-b"))
        val first = ChosenSemanticResponseV1.from(
            firstDomain,
            YesNoResponse("decision-a", choice = true),
        )
        val second = ChosenSemanticResponseV1.from(
            secondDomain,
            YesNoResponse("decision-b", choice = true),
        )

        first shouldBe second
        first.canonicalJson().contains("decisionId").shouldBeFalse()
        SemanticReplayPrefixV1(
            inputs = listOf(SemanticReplayInputV1.response(first))
        ).digest() shouldBe SemanticReplayPrefixV1(
            inputs = listOf(SemanticReplayInputV1.response(second))
        ).digest()
    }

    test("semantic replay inputs reject session, continuation, nonce, and runtime handles") {
        val invalid = buildJsonObject {
            put("type", "PassPriority")
            put("sessionId", "session")
            put("continuationNonce", "nonce")
        }

        shouldThrow<IllegalArgumentException> {
            SemanticReplayInputV1(
                kind = SemanticReplayInputKind.ACTION,
                semanticValue = invalid,
            )
        }
    }

    test("stable abilityKey keeps generated runtime allocation out of the chosen action") {
        val environment = environment()
        val source = observation(environment)
        fun variant(actionId: Int) = source.copy(
            legalActions = listOf(
                source.legalActions.first().copy(
                    actionId = actionId,
                    actionSemantics = buildJsonObject {
                        put("type", "ActivateAbility")
                        put("abilityKey", buildJsonObject {
                            put("origin", "intrinsic")
                            put("ordinal", 0)
                        })
                    },
                )
            )
        )
        val firstDomain = CompleteLegalDomainV1.from(variant(1))
        val first = ChosenSemanticActionV1.from(firstDomain, firstDomain.candidates.first())
        val secondDomain = CompleteLegalDomainV1.from(variant(2))
        val second = ChosenSemanticActionV1.from(secondDomain, secondDomain.candidates.first())

        first shouldBe second
    }

    test("policy provenance and collection identity are absent from semantic decision identity") {
        val prefix = SemanticReplayPrefixV1()
        val first = identity(prefix)
        val second = identity(prefix)
        val collectionJobA = "collection-a"
        val collectionJobB = "collection-b"

        collectionJobA shouldNotBe collectionJobB
        first.semanticDecisionId() shouldBe second.semanticDecisionId()
    }

    test("semantic prefix action changes alter the prefix and decision identity") {
        val environment = environment()
        val source = observation(environment)
        val changed = source.copy(
            legalActions = source.legalActions.mapIndexed { index, action ->
                if (index == 0) action.copy(affordable = !action.affordable) else action
            }
        )
        val firstDomain = CompleteLegalDomainV1.from(source)
        val secondDomain = CompleteLegalDomainV1.from(changed)
        val first = ChosenSemanticActionV1.from(firstDomain, firstDomain.candidates.first())
        val second = ChosenSemanticActionV1.from(secondDomain, secondDomain.candidates.first())

        val firstPrefix = SemanticReplayPrefixV1(inputs = listOf(SemanticReplayInputV1.action(first)))
        val secondPrefix = SemanticReplayPrefixV1(inputs = listOf(SemanticReplayInputV1.action(second)))
        firstPrefix.digest() shouldNotBe secondPrefix.digest()
        identity(firstPrefix) shouldNotBe identity(secondPrefix)
    }

    test("replay action order and history are bound") {
        val environment = environment()
        val source = observation(environment)
        val firstAction = source.legalActions.first()
        val domain = CompleteLegalDomainV1.from(
            source.copy(
                legalActions = listOf(
                    firstAction,
                    firstAction.copy(
                        actionId = firstAction.actionId + 1,
                        affordable = !firstAction.affordable,
                    ),
                )
            )
        )
        val first = ChosenSemanticActionV1.from(domain, domain.candidates[0])
        val second = ChosenSemanticActionV1.from(domain, domain.candidates[1])
        val ordered = SemanticReplayPrefixV1(
            inputs = listOf(SemanticReplayInputV1.action(first), SemanticReplayInputV1.action(second))
        )
        val reordered = SemanticReplayPrefixV1(
            inputs = listOf(SemanticReplayInputV1.action(second), SemanticReplayInputV1.action(first))
        )

        ordered.digest() shouldNotBe reordered.digest()
    }

    test("perspective, actor, decision kind, observation, and domain are identity inputs") {
        val prefix = SemanticReplayPrefixV1()
        val baseline = identity(prefix)
        identity(prefix, perspectivePlayerId = "player-b") shouldNotBe baseline
        identity(prefix, decisionKind = "CHOOSE_TARGETS") shouldNotBe baseline
        identity(prefix, observationDigest = "d".repeat(64)) shouldNotBe baseline
        identity(prefix, candidateDomainDigest = "e".repeat(64)) shouldNotBe baseline
        identity(prefix, replayActionIndex = 1) shouldNotBe baseline
        SemanticDecisionIdentityV1(
            semanticEpisodeId = "f".repeat(64),
            replayPrefixDigest = prefix.digest().value,
            replayActionIndex = 0,
            perspectivePlayerId = "player-a",
            decisionKind = "PRIORITY",
            observationDigest = OBSERVATION_DIGEST,
            candidateDomainDigest = "c".repeat(64),
        ) shouldNotBe baseline
    }

    test("semantic decision preimage contains exactly the normative identity fields") {
        val input = identity(SemanticReplayPrefixV1())
        val encoded = A3SemanticJson.strictJson.parseToJsonElement(input.canonicalJson()).jsonObject

        encoded.keys shouldBe setOf(
            "schema",
            "semanticEpisodeId",
            "replayPrefixDigest",
            "replayActionIndex",
            "perspectivePlayerId",
            "decisionKind",
            "observationDigest",
            "candidateDomainDigest",
        )
        encoded["collectionJobId"] shouldBe null
        encoded["policySeed"] shouldBe null
        encoded["trajectoryId"] shouldBe null
    }

    test("malformed semantic identity inputs fail closed") {
        shouldThrow<IllegalArgumentException> {
            identity(SemanticReplayPrefixV1(), observationDigest = "A".repeat(64))
        }
        shouldThrow<IllegalArgumentException> {
            identity(SemanticReplayPrefixV1(), replayActionIndex = -1)
        }
        shouldThrow<IllegalArgumentException> {
            identity(SemanticReplayPrefixV1(), decisionKind = "")
        }
    }

    test("an action outside the stored domain is rejected") {
        val environment = environment()
        val domain = actionDomain(environment)
        val other = CompleteLegalDomainV1.from(
            observation(environment).copy(
                legalActions = listOf(
                    observation(environment).legalActions.first().copy(
                        actionSemantics = buildJsonObject {
                            put("type", "DifferentSemanticAction")
                        }
                    )
                )
            )
        )

        shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.from(domain, other.candidates.first())
        }
    }

    test("required action payloads must be present and contain no unknown fields") {
        val environment = environment()
        val sourceDomain = actionDomain(environment)
        val candidate = candidateWithRequiredPayload(sourceDomain, listOf("targets"))
        val domain = CompleteLegalDomainV1(
            kind = CompleteLegalDomainKind.ACTION_CANDIDATES,
            candidates = listOf(candidate),
        )

        ChosenSemanticActionV1.from(
            domain,
            candidate,
            buildJsonObject { put("targets", buildJsonArray {}) },
        )
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.from(domain, candidate, buildJsonObject {})
        }
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.from(domain, candidate, buildJsonObject {
                put("targets", buildJsonArray {})
                put("unknown", true)
            })
        }
    }

    test("partial structured targets are rejected while a complete response is accepted") {
        val domain = CompleteLegalDomainV1(
            kind = CompleteLegalDomainKind.STRUCTURED_DECISION,
            decisionKind = PendingDecisionKind.CHOOSE_TARGETS,
            shape = DecisionShape(),
            structuredDomain = targetDomain(),
        )

        shouldThrow<IllegalArgumentException> {
            ChosenSemanticResponseV1.from(domain, TargetsResponse("decision", emptyMap()))
        }
        val chosen = ChosenSemanticResponseV1.from(
            domain,
            TargetsResponse("decision", mapOf(0 to listOf(EntityId("target")))),
        )
        chosen.canonicalJson().contains("target").shouldBeTrue()
    }

    test("raw AutoPay and advisory autoPaySuggestion are not trusted choices") {
        val environment = environment()
        val sourceDomain = actionDomain(environment)
        val candidate = candidateWithRequiredPayload(sourceDomain, listOf("paymentStrategy"))
        val domain = CompleteLegalDomainV1(
            kind = CompleteLegalDomainKind.ACTION_CANDIDATES,
            candidates = listOf(candidate),
        )

        shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.from(domain, candidate, buildJsonObject {
                put("paymentStrategy", buildJsonObject { put("type", "AutoPay") })
            })
        }
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.from(domain, candidate, buildJsonObject {
                put("autoPaySuggestion", buildJsonArray {})
            })
        }
    }

    test("explicit mana-source responses retain the manual choice and reject AutoPay") {
        val domain = CompleteLegalDomainV1(
            kind = CompleteLegalDomainKind.STRUCTURED_DECISION,
            decisionKind = PendingDecisionKind.SELECT_MANA_SOURCES,
            shape = DecisionShape(),
            structuredDomain = ManaSourcesDomain(
                availableSources = listOf(
                    ManaSourceDomain(
                        entityId = EntityId("source"),
                        name = "presentation",
                        producesColors = setOf(com.wingedsheep.sdk.core.Color.RED),
                        producesColorless = false,
                        requiresSacrifice = false,
                        requiresTappingAnotherPermanent = false,
                        manaAbilityKey = "stable-source-key",
                    )
                ),
                requiredCost = "{R}",
                autoPaySuggestion = emptyList(),
                canDecline = false,
                waterbendPermanents = emptyList(),
            ),
        )

        val chosen = ChosenSemanticResponseV1.from(
            domain,
            ManaSourcesSelectedResponse(
                decisionId = "decision",
                selectedSources = listOf(EntityId("source")),
                autoPay = false,
            ),
        )
        chosen.canonicalJson().contains("selectedSources").shouldBeTrue()
        chosen.canonicalJson().contains("autoPay").shouldBeFalse()

        shouldThrow<IllegalArgumentException> {
            ChosenSemanticResponseV1.from(
                domain,
                ManaSourcesSelectedResponse(
                    decisionId = "decision",
                    autoPay = true,
                ),
            )
        }
    }

    test("an explicit V3 payment choice is retained in full") {
        val environment = environment()
        val sourceDomain = actionDomain(environment)
        val candidate = candidateWithRequiredPayload(sourceDomain, listOf("paymentStrategy"))
        val domain = CompleteLegalDomainV1(
            kind = CompleteLegalDomainKind.ACTION_CANDIDATES,
            candidates = listOf(candidate),
        )
        val explicit = buildJsonObject {
            put("paymentStrategy", buildJsonObject {
                put("type", "ExplicitV3")
                put("paymentPlan", buildJsonObject {
                    put("activations", buildJsonArray {})
                    put("outerAllocation", buildJsonArray {})
                })
            })
        }

        val chosen = ChosenSemanticActionV1.from(domain, candidate, explicit)
        chosen.canonicalJson().contains("ExplicitV3").shouldBeTrue()
        chosen.canonicalJson().contains("outerAllocation").shouldBeTrue()
    }

    test("folded responses retain every semantic response field but omit decision IDs") {
        val environment = environment()
        val domain = CompleteLegalDomainV1.from(foldedObservation(environment, "decision-a"))
        val chosen = ChosenSemanticResponseV1.from(domain, YesNoResponse("decision-a", true))
        val canonical = chosen.canonicalJson()

        canonical.contains("YesNoResponse").shouldBeTrue()
        canonical.contains("choice").shouldBeTrue()
        canonical.contains("decisionId").shouldBeFalse()
    }

    test("unknown folded response fields fail closed") {
        val environment = environment()
        val domain = CompleteLegalDomainV1.from(foldedObservation(environment, "decision-a"))
        val response = buildJsonObject {
            put("type", "YesNoResponse")
            put("choice", true)
            put("unknown", true)
        }

        shouldThrow<IllegalArgumentException> {
            ChosenSemanticResponseV1.from(domain, response)
        }
    }

    test("legal structured empty selection remains explicit") {
        val domain = CompleteLegalDomainV1(
            kind = CompleteLegalDomainKind.STRUCTURED_DECISION,
            decisionKind = PendingDecisionKind.SELECT_CARDS,
            shape = DecisionShape(minSelections = 0, maxSelections = 1),
            structuredDomain = com.wingedsheep.gym.contract.CardSelectionDomain(
                options = listOf(EntityId("card")),
                minSelections = 0,
                maxSelections = 1,
                ordered = false,
                cardInfo = null,
                useTargetingUI = false,
                selectedLabel = null,
                remainderLabel = null,
                nonSelectableOptions = emptyList(),
                onePerCardType = false,
                onePerColor = false,
                availableColors = null,
                onePerCardName = false,
                onePerBasicLandType = false,
                onePerPower = false,
                maxTotalManaValue = null,
                minTotalManaValue = null,
                maxTotalPower = null,
                conditionalMinimums = emptyList(),
            ),
        )
        val chosen = ChosenSemanticResponseV1.from(domain, CardsSelectedResponse("decision", emptyList()))

        chosen.canonicalJson().contains("selectedCards").shouldBeTrue()
        chosen.canonicalJson().contains("[]").shouldBeTrue()
    }

    test("unknown replay-input and decision-identity versions fail closed") {
        shouldThrow<IllegalArgumentException> {
            SemanticReplayInputV1(
                version = SEMANTIC_REPLAY_INPUT_V1_VERSION + 1,
                kind = SemanticReplayInputKind.ACTION,
                semanticValue = buildJsonObject { put("type", "PassPriority") },
            )
        }
        shouldThrow<IllegalArgumentException> {
            SemanticDecisionIdentityV1(
                version = SEMANTIC_DECISION_IDENTITY_V1_VERSION + 1,
                semanticEpisodeId = SEMANTIC_EPISODE,
                replayPrefixDigest = "0".repeat(64),
                replayActionIndex = 0,
                perspectivePlayerId = "player-a",
                decisionKind = "PRIORITY",
                observationDigest = OBSERVATION_DIGEST,
                candidateDomainDigest = "c".repeat(64),
            )
        }
    }

    test("A1 source and trigger changes bind through observation digest, presentation does not") {
        val environment = environment()
        val source = observation(environment)
        val player = environment.playerIds.first()
        val sourceEntity = environment.state.getHand(player).first()
        val triggerEntity = environment.state.getHand(player).last()
        fun withPending(
            sourceEntityId: EntityId?,
            triggeringEntityId: EntityId?,
            prompt: String = "prompt",
            sourceName: String? = "source",
            effectHint: String? = "hint",
        ): PlayerObservationV1 {
            val pending = PendingDecisionView(
                decisionId = "routing",
                kind = PendingDecisionKind.YES_NO,
                playerId = player,
                prompt = prompt,
                sourceEntityId = sourceEntityId,
                sourceName = sourceName,
                triggeringEntityId = triggeringEntityId,
                effectHint = effectHint,
                requiresStructuredResponse = false,
            )
            val withoutDigest = source.copy(
                pendingDecision = pending,
                stateDigest = "",
            )
            return PlayerObservationV1.from(
                withoutDigest.copy(stateDigest = StateDigest.compute(withoutDigest))
            )
        }

        val baseline = withPending(sourceEntity, triggerEntity)
        val sourceChanged = withPending(EntityId("different-source"), triggerEntity)
        val triggerChanged = withPending(sourceEntity, EntityId("different-trigger"))
        val presentationChanged = withPending(
            sourceEntity,
            triggerEntity,
            prompt = "different prompt",
            sourceName = "different source",
            effectHint = "different hint",
        )
        val prefix = SemanticReplayPrefixV1()

        identity(prefix, observationDigest = sourceChanged.observationDigest) shouldNotBe
            identity(prefix, observationDigest = baseline.observationDigest)
        identity(prefix, observationDigest = triggerChanged.observationDigest) shouldNotBe
            identity(prefix, observationDigest = baseline.observationDigest)
        presentationChanged.observationDigest shouldBe baseline.observationDigest
        identity(prefix, observationDigest = presentationChanged.observationDigest) shouldBe
            identity(prefix, observationDigest = baseline.observationDigest)
    }
})
