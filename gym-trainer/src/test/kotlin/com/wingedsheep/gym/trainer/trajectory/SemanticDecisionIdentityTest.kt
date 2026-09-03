package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.AtomicManaCostUnitV1
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DamageEdgeAmount
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.InitialPoolBucketKeyV1
import com.wingedsheep.engine.core.InitialPoolBucketV1
import com.wingedsheep.engine.core.ManaResourceRefV1
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PaymentAllocationV1
import com.wingedsheep.engine.core.PaymentCostKindV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentTargetV1
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.contract.CompleteLegalDomainKind
import com.wingedsheep.gym.contract.CompleteLegalDomainV1
import com.wingedsheep.gym.contract.DecisionShape
import com.wingedsheep.gym.contract.ActionTargetDomainV1
import com.wingedsheep.gym.contract.AttackBandConstraintsV1
import com.wingedsheep.gym.contract.AttackDeclarationDomainV2
import com.wingedsheep.gym.contract.BlockerDeclarationDomainV1
import com.wingedsheep.gym.contract.CardSelectionDomain
import com.wingedsheep.gym.contract.CombatAttackerDomain
import com.wingedsheep.gym.contract.CombatDamageDirection
import com.wingedsheep.gym.contract.CombatDamageEdgeDomain
import com.wingedsheep.gym.contract.CombatDefenderDomain
import com.wingedsheep.gym.contract.CombatResolutionDomain
import com.wingedsheep.gym.contract.CombatTargetKind
import com.wingedsheep.gym.contract.ConditionalSelectionMinimumDomain
import com.wingedsheep.gym.contract.ManaSourceDomain
import com.wingedsheep.gym.contract.ManaSourcesDomain
import com.wingedsheep.gym.contract.PendingDecisionKind
import com.wingedsheep.gym.contract.PendingDecisionView
import com.wingedsheep.gym.contract.PlayerObservationV1
import com.wingedsheep.gym.contract.StateDigest
import com.wingedsheep.gym.contract.StructuredCardInfo
import com.wingedsheep.gym.contract.TargetRequirementDomain
import com.wingedsheep.gym.contract.TargetPaymentBindingV1
import com.wingedsheep.gym.contract.TargetPaymentDomainV1
import com.wingedsheep.gym.contract.TargetsDomain
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
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
        perspectivePlayerId: String? = null,
        decisionKind: SemanticDecisionKindV1? = null,
        observationDigest: String = OBSERVATION_DIGEST,
        semanticEpisodeId: String = SEMANTIC_EPISODE,
        observationOverride: PlayerObservationV1? = null,
        domainOverride: CompleteLegalDomainV1? = null,
    ): SemanticDecisionIdentityV1 {
        val environment = environment()
        val source = observation(environment)
        return SemanticDecisionIdentityV1.from(
            semanticEpisodeId = semanticEpisodeId,
            prefix = prefix,
            replayActionIndex = replayActionIndex,
            perspectivePlayerId = perspectivePlayerId,
            decisionKind = decisionKind,
            observation = observationOverride
                ?: PlayerObservationV1.from(source).copy(observationDigest = observationDigest),
            domain = domainOverride ?: CompleteLegalDomainV1.from(source),
        )
    }

    fun replaceJsonValue(objectValue: JsonObject, key: String, value: kotlinx.serialization.json.JsonElement) =
        buildJsonObject {
            var replaced = false
            objectValue.forEach { (existingKey, existingValue) ->
                if (existingKey == key) replaced = true
                put(existingKey, if (existingKey == key) value else existingValue)
            }
            if (!replaced) put(key, value)
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

    fun actionTargetDomain(): ActionTargetDomainV1 = ActionTargetDomainV1(
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
        )
    )

    fun attackDomain(): AttackDeclarationDomainV2 = AttackDeclarationDomainV2(
        attackerOrder = listOf(EntityId("attacker-a"), EntityId("attacker-b")),
        attackerToDefenders = mapOf(
            EntityId("attacker-a") to listOf(EntityId("defender")),
            EntityId("attacker-b") to listOf(EntityId("defender")),
        ),
        mandatoryAttackers = emptyList(),
        canDeclareZeroAttackers = true,
        maxAttackers = 2,
        coAttackerRequirements = emptyMap(),
        bandConstraints = AttackBandConstraintsV1(
            bandingAttackersByDefender = mapOf(
                EntityId("defender") to listOf(EntityId("attacker-a"), EntityId("attacker-b")),
            ),
            nonBandingAttackersByDefender = emptyMap(),
        ),
    )

    fun blockerDomain(): BlockerDeclarationDomainV1 = BlockerDeclarationDomainV1(
        blockerOrder = listOf(EntityId("blocker-a")),
        attackerOrder = listOf(EntityId("attacker-a")),
        blockerToAttackers = mapOf(EntityId("blocker-a") to listOf(EntityId("attacker-a"))),
        maxAttackersByBlocker = mapOf(EntityId("blocker-a") to 1),
        minBlockersByAttacker = emptyMap(),
        maxBlockersByAttacker = emptyMap(),
        globalMaxBlockers = null,
        coBlockerRequirements = emptyMap(),
        requirements = emptyList(),
        minimumSatisfiedRequirementCount = 0,
        canDeclareZeroBlockers = true,
    )

    fun paymentDomain(): com.wingedsheep.gym.contract.PaymentDomainV5 {
        val redBucket = InitialPoolBucketKeyV1.UnrestrictedPoolBucket(PaymentManaColor.RED)
        return com.wingedsheep.gym.contract.PaymentDomainV5(
            requiredCost = "{R}",
            outerAtomicCostUnits = listOf(
                AtomicManaCostUnitV1(
                    symbolIndex = 0,
                    unitIndexWithinSymbol = 0,
                    kind = PaymentCostKindV1.COLORED,
                    allowedColors = setOf(PaymentManaColor.RED),
                )
            ),
            initialPoolBuckets = listOf(
                InitialPoolBucketV1(key = redBucket, availableAmount = 1)
            ),
            sourceActivationOptions = emptyList(),
        )
    }

    fun explicitPaymentPayload(plan: PaymentPlanV3): JsonObject = buildJsonObject {
        put("paymentStrategy", A3SemanticJson.strictJson.encodeToJsonElement(
            PaymentStrategy.serializer(),
            PaymentStrategy.ExplicitV3(plan),
        ))
    }

    fun actionTargetDomainJson(): JsonObject {
        val encoded = A3SemanticJson.strictJson.encodeToJsonElement(
            ActionTargetDomainV1.serializer(),
            actionTargetDomain(),
        ).jsonObject
        return replaceJsonValue(
            encoded,
            "requirements",
            kotlinx.serialization.json.JsonArray(
                encoded.getValue("requirements").jsonArray.map { requirement ->
                    JsonObject(requirement.jsonObject.filterKeys { it != "description" })
                }
            ),
        )
    }

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
        identity(prefix, observationDigest = "d".repeat(64)) shouldNotBe baseline
        identity(prefix, semanticEpisodeId = "f".repeat(64)) shouldNotBe baseline

        val environment = environment()
        val source = observation(environment)
        val nonEmptyPrefix = SemanticReplayPrefixV1(
            inputs = listOf(
                SemanticReplayInputV1.action(
                    ChosenSemanticActionV1.from(
                        CompleteLegalDomainV1.from(source),
                        CompleteLegalDomainV1.from(source).candidates.first(),
                    )
                )
            )
        )
        identity(nonEmptyPrefix) shouldNotBe baseline
        val alternatePerspective = PlayerObservationV1.from(source).copy(
            perspectivePlayerId = EntityId("player-b"),
        )
        identity(prefix, observationOverride = alternatePerspective) shouldNotBe baseline

        val candidateChanged = source.copy(
            legalActions = source.legalActions.mapIndexed { index, action ->
                if (index == 0) action.copy(affordable = !action.affordable) else action
            }
        )
        identity(
            prefix,
            domainOverride = CompleteLegalDomainV1.from(candidateChanged),
        ) shouldNotBe baseline

        val foldedSource = foldedObservation(environment, "decision")
        identity(
            prefix,
            observationOverride = PlayerObservationV1.from(foldedSource),
            domainOverride = CompleteLegalDomainV1.from(foldedSource),
        ) shouldNotBe baseline
    }

    test("identity rejects forged perspective and decision kind assertions") {
        val environment = environment()
        val source = observation(environment)
        val projection = PlayerObservationV1.from(source)
        val domain = CompleteLegalDomainV1.from(source)

        shouldThrow<IllegalArgumentException> {
            SemanticDecisionIdentityV1.from(
                semanticEpisodeId = SEMANTIC_EPISODE,
                prefix = SemanticReplayPrefixV1(),
                observation = projection,
                domain = domain,
                perspectivePlayerId = "forged-perspective",
            )
        }
        shouldThrow<IllegalArgumentException> {
            SemanticDecisionIdentityV1.from(
                semanticEpisodeId = SEMANTIC_EPISODE,
                prefix = SemanticReplayPrefixV1(),
                observation = projection,
                domain = domain,
                decisionKind = SemanticDecisionKindV1.CHOOSE_TARGETS,
            )
        }
    }

    test("unknown decision kinds and observation-domain pending mismatches fail closed") {
        val baseline = identity(SemanticReplayPrefixV1())
        val encoded = A3SemanticJson.strictJson.encodeToJsonElement(
            SemanticDecisionIdentityV1.serializer(),
            baseline,
        ).jsonObject
        val unknownKind = replaceJsonValue(encoded, "decisionKind", JsonPrimitive("FUTURE_KIND"))

        shouldThrow<IllegalArgumentException> {
            A3SemanticJson.strictJson.decodeFromJsonElement(
                SemanticDecisionIdentityV1.serializer(),
                unknownKind,
            )
        }

        val environment = environment()
        val source = foldedObservation(environment, "decision")
        val mismatchedObservation = PlayerObservationV1.from(
            source.copy(
                pendingDecision = source.pendingDecision!!.copy(
                    kind = PendingDecisionKind.CHOOSE_TARGETS,
                ),
            )
        )
        shouldThrow<IllegalArgumentException> {
            SemanticDecisionIdentityV1.from(
                semanticEpisodeId = SEMANTIC_EPISODE,
                prefix = SemanticReplayPrefixV1(),
                observation = mismatchedObservation,
                domain = CompleteLegalDomainV1.from(source),
            )
        }
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
            identity(SemanticReplayPrefixV1(), perspectivePlayerId = "")
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

    test("stored action payloads must satisfy the public candidate domains") {
        val environment = environment()
        val sourceDomain = actionDomain(environment)
        fun domain(candidate: JsonObject) = CompleteLegalDomainV1(
            kind = CompleteLegalDomainKind.ACTION_CANDIDATES,
            candidates = listOf(candidate),
        )

        val targetCandidate = replaceJsonValue(
            candidateWithRequiredPayload(sourceDomain, listOf("targets")),
            "targetDomain",
            actionTargetDomainJson(),
        )
        val targetPayload = buildJsonArray {
            add(buildJsonObject {
                put("type", "Permanent")
                put("entityId", "target")
            })
        }
        ChosenSemanticActionV1.from(domain(targetCandidate), targetCandidate, buildJsonObject {
            put("targets", targetPayload)
        })
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.from(domain(targetCandidate), targetCandidate, buildJsonObject {
                put("targets", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "Permanent")
                        put("entityId", "outside")
                    })
                })
            })
        }

        val xCandidate = replaceJsonValue(
            replaceJsonValue(
                candidateWithRequiredPayload(sourceDomain, listOf("xValue")),
                "hasXCost",
                JsonPrimitive(true),
            ),
            "maxAffordableX",
            JsonPrimitive(3),
        )
        ChosenSemanticActionV1.from(domain(xCandidate), xCandidate, buildJsonObject {
            put("xValue", 2)
        })
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.from(domain(xCandidate), xCandidate, buildJsonObject {
                put("xValue", 4)
            })
        }

        val colorCandidate = replaceJsonValue(
            candidateWithRequiredPayload(sourceDomain, listOf("manaColorChoice")),
            "availableManaColors",
            buildJsonArray { add(JsonPrimitive("RED")) },
        )
        ChosenSemanticActionV1.from(domain(colorCandidate), colorCandidate, buildJsonObject {
            put("manaColorChoice", "RED")
        })
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.from(domain(colorCandidate), colorCandidate, buildJsonObject {
                put("manaColorChoice", "BLUE")
            })
        }

        val attackCandidate = replaceJsonValue(
            replaceJsonValue(
                candidateWithRequiredPayload(sourceDomain, listOf("attackers", "bands")),
                "actionSemantics",
                buildJsonObject { put("type", "DeclareAttackers") },
            ),
            "attackDeclarationDomain",
            A3SemanticJson.strictJson.encodeToJsonElement(
                AttackDeclarationDomainV2.serializer(),
                attackDomain(),
            ),
        )
        val validAttack = buildJsonObject {
            put("attackers", buildJsonObject {
                put("attacker-a", "defender")
                put("attacker-b", "defender")
            })
            put("bands", buildJsonArray {
                add(buildJsonArray {
                    add(JsonPrimitive("attacker-a"))
                    add(JsonPrimitive("attacker-b"))
                })
            })
        }
        ChosenSemanticActionV1.from(domain(attackCandidate), attackCandidate, validAttack)
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.from(domain(attackCandidate), attackCandidate, buildJsonObject {
                put("attackers", buildJsonObject { put("attacker-a", "defender") })
                put("bands", buildJsonArray {
                    add(buildJsonArray {
                        add(JsonPrimitive("attacker-a"))
                        add(JsonPrimitive("attacker-a"))
                    })
                })
            })
        }

        val blockerCandidate = replaceJsonValue(
            candidateWithRequiredPayload(sourceDomain, listOf("blockers")),
            "blockerDeclarationDomain",
            A3SemanticJson.strictJson.encodeToJsonElement(
                BlockerDeclarationDomainV1.serializer(),
                blockerDomain(),
            ),
        )
            ChosenSemanticActionV1.from(domain(blockerCandidate), blockerCandidate, buildJsonObject {
                put("blockers", buildJsonObject {
                put("blocker-a", buildJsonArray { add(JsonPrimitive("attacker-a")) })
                })
        })
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.from(domain(blockerCandidate), blockerCandidate, buildJsonObject {
                put("blockers", buildJsonObject {
                    put("blocker-a", buildJsonArray { add(JsonPrimitive("outside")) })
                })
            })
        }

        val orderCandidate = replaceJsonValue(
            replaceJsonValue(
                candidateWithRequiredPayload(sourceDomain, listOf("orderedBlockers")),
                "actionSemantics",
                buildJsonObject {
                    put("type", "OrderBlockers")
                    put("attackerId", "attacker-a")
                },
            ),
            "blockerDeclarationDomain",
            A3SemanticJson.strictJson.encodeToJsonElement(
                BlockerDeclarationDomainV1.serializer(),
                blockerDomain(),
            ),
        )
        ChosenSemanticActionV1.from(domain(orderCandidate), orderCandidate, buildJsonObject {
            put("orderedBlockers", buildJsonArray { add(JsonPrimitive("blocker-a")) })
        })
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.from(domain(orderCandidate), orderCandidate, buildJsonObject {
                put("orderedBlockers", buildJsonArray { add(JsonPrimitive("outside")) })
            })
        }

        val payment = paymentDomain()
        val paymentCandidate = replaceJsonValue(
            candidateWithRequiredPayload(sourceDomain, listOf("paymentStrategy")),
            "paymentDomain",
            A3SemanticJson.strictJson.encodeToJsonElement(
                com.wingedsheep.gym.contract.PaymentDomainV5.serializer(),
                payment,
            ),
        )
        val redBucket = InitialPoolBucketKeyV1.UnrestrictedPoolBucket(PaymentManaColor.RED)
        val blueBucket = InitialPoolBucketKeyV1.UnrestrictedPoolBucket(PaymentManaColor.BLUE)
        val validPlan = PaymentPlanV3(
            outerAllocation = listOf(
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(0, 0),
                    resource = ManaResourceRefV1.InitialPoolResource(redBucket),
                )
            )
        )
        val invalidPlan = validPlan.copy(
            outerAllocation = listOf(
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(0, 0),
                    resource = ManaResourceRefV1.InitialPoolResource(blueBucket),
                )
            )
        )
        ChosenSemanticActionV1.from(
            domain(paymentCandidate),
            paymentCandidate,
            explicitPaymentPayload(validPlan),
        )
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.from(
                domain(paymentCandidate),
                paymentCandidate,
                explicitPaymentPayload(invalidPlan),
            )
        }

        val targetPaymentCandidate = replaceJsonValue(
            replaceJsonValue(
                candidateWithRequiredPayload(sourceDomain, listOf("targets", "paymentStrategy")),
                "targetDomain",
                actionTargetDomainJson(),
            ),
            "targetPaymentDomain",
            A3SemanticJson.strictJson.encodeToJsonElement(
                TargetPaymentDomainV1.serializer(),
                TargetPaymentDomainV1(
                    targetBindings = listOf(
                        TargetPaymentBindingV1(
                            target = EntityId("target"),
                            affordable = true,
                            paymentDomain = payment,
                        )
                    )
                ),
            ),
        )
        ChosenSemanticActionV1.from(
            domain(targetPaymentCandidate),
            targetPaymentCandidate,
            buildJsonObject {
                put("targets", targetPayload)
                put("paymentStrategy", explicitPaymentPayload(validPlan)["paymentStrategy"]!!)
            },
        )
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.from(
                domain(targetPaymentCandidate),
                targetPaymentCandidate,
                buildJsonObject {
                    put("targets", targetPayload)
                    put("paymentStrategy", explicitPaymentPayload(invalidPlan)["paymentStrategy"]!!)
                },
            )
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

        val stateDependent = domain.copy(
            structuredDomain = targetDomain().copy(
                requirements = targetDomain().requirements.map { it.copy(sameController = true) }
            )
        )
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticResponseV1.from(
                stateDependent,
                TargetsResponse("decision", mapOf(0 to listOf(EntityId("target")))),
            )
        }
    }

    test("stored card-selection constraints are enforced from public cardInfo") {
        val a = EntityId("a")
        val b = EntityId("b")
        val c = EntityId("c")
        val d = EntityId("d")
        val e = EntityId("e")
        val infos = mapOf(
            a to StructuredCardInfo("Alpha", "{2}", "Creature — Human", colors = listOf("RED"), power = 2),
            b to StructuredCardInfo("Beta", "{3}", "Creature — Elf", colors = listOf("RED"), power = 2),
            c to StructuredCardInfo("Alpha", "{1}", "Artifact", colors = listOf("BLUE"), power = 3),
            d to StructuredCardInfo("Forest One", "", "Basic Land — Forest", power = null),
            e to StructuredCardInfo("Forest Two", "", "Basic Land — Forest", power = null),
        )
        fun domain(
            options: List<EntityId>,
            minSelections: Int = 0,
            maxSelections: Int = options.size,
            cardInfo: Map<EntityId, StructuredCardInfo>? = infos,
            onePerCardType: Boolean = false,
            onePerColor: Boolean = false,
            onePerCardName: Boolean = false,
            onePerBasicLandType: Boolean = false,
            onePerPower: Boolean = false,
            maxTotalManaValue: Int? = null,
            minTotalManaValue: Int? = null,
            maxTotalPower: Int? = null,
            conditionalMinimums: List<ConditionalSelectionMinimumDomain> = emptyList(),
        ): CompleteLegalDomainV1 = CompleteLegalDomainV1(
            kind = CompleteLegalDomainKind.STRUCTURED_DECISION,
            decisionKind = PendingDecisionKind.SELECT_CARDS,
            shape = DecisionShape(
                minSelections = minSelections,
                maxSelections = maxSelections,
            ),
            structuredDomain = CardSelectionDomain(
                options = options,
                minSelections = minSelections,
                maxSelections = maxSelections,
                ordered = false,
                cardInfo = cardInfo,
                useTargetingUI = false,
                selectedLabel = null,
                remainderLabel = null,
                nonSelectableOptions = emptyList(),
                onePerCardType = onePerCardType,
                onePerColor = onePerColor,
                availableColors = null,
                onePerCardName = onePerCardName,
                onePerBasicLandType = onePerBasicLandType,
                onePerPower = onePerPower,
                maxTotalManaValue = maxTotalManaValue,
                minTotalManaValue = minTotalManaValue,
                maxTotalPower = maxTotalPower,
                conditionalMinimums = conditionalMinimums,
            ),
        )
        fun response(selected: List<EntityId>) = CardsSelectedResponse("decision", selected)

        shouldThrow<IllegalArgumentException> {
            ChosenSemanticResponseV1.from(domain(listOf(a, b), minSelections = 2, maxSelections = 2, onePerCardType = true), response(listOf(a, b)))
        }
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticResponseV1.from(domain(listOf(a, b), minSelections = 2, maxSelections = 2, onePerColor = true), response(listOf(a, b)))
        }
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticResponseV1.from(domain(listOf(a, c), minSelections = 2, maxSelections = 2, onePerCardName = true), response(listOf(a, c)))
        }
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticResponseV1.from(domain(listOf(d, e), minSelections = 2, maxSelections = 2, onePerBasicLandType = true), response(listOf(d, e)))
        }
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticResponseV1.from(domain(listOf(a, b), minSelections = 2, maxSelections = 2, onePerPower = true), response(listOf(a, b)))
        }
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticResponseV1.from(domain(listOf(a, b), minSelections = 1, maxSelections = 2, maxTotalManaValue = 4), response(listOf(a, b)))
        }
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticResponseV1.from(domain(listOf(a, b), minSelections = 1, maxSelections = 2, minTotalManaValue = 6), response(listOf(a, b)))
        }
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticResponseV1.from(domain(listOf(a, b), minSelections = 2, maxSelections = 2, maxTotalPower = 3), response(listOf(a, b)))
        }
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticResponseV1.from(
                domain(
                    listOf(a, b),
                    minSelections = 1,
                    maxSelections = 2,
                    conditionalMinimums = listOf(
                        ConditionalSelectionMinimumDomain(
                            requiredSelections = 2,
                            minimumSelections = 2,
                            matchingOptions = listOf(a),
                            requiredMatches = 1,
                            description = null,
                        )
                    ),
                ),
                response(listOf(b)),
            )
        }
        ChosenSemanticResponseV1.from(
            domain(
                listOf(a, b),
                minSelections = 1,
                maxSelections = 2,
                conditionalMinimums = listOf(
                    ConditionalSelectionMinimumDomain(
                        requiredSelections = 2,
                        minimumSelections = 2,
                        matchingOptions = listOf(a),
                        requiredMatches = 1,
                        description = null,
                    )
                ),
            ),
            response(listOf(a, b)),
        )
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticResponseV1.from(
                domain(listOf(a, b), minSelections = 1, maxSelections = 2, cardInfo = null, onePerColor = true),
                response(listOf(a)),
            )
        }
    }

    test("combat responses validate stored edge relations and source totals") {
        val attacker = EntityId("attacker")
        val defender = EntityId("defender")
        val domain = CompleteLegalDomainV1(
            kind = CompleteLegalDomainKind.STRUCTURED_DECISION,
            decisionKind = PendingDecisionKind.COMBAT_RESOLUTION,
            shape = DecisionShape(),
            structuredDomain = CombatResolutionDomain(
                firstStrike = false,
                attackers = listOf(
                    CombatAttackerDomain(
                        id = attacker,
                        name = "attacker",
                        power = 2,
                        toughness = 2,
                        hasTrample = false,
                        hasDeathtouch = false,
                        hasFirstStrike = false,
                        hasDoubleStrike = false,
                        dealsDamageThisStep = true,
                        bandId = null,
                        attackedDefenderId = defender,
                        blockedByIds = emptyList(),
                        markedDamage = 0,
                    )
                ),
                blockers = emptyList(),
                defenders = listOf(
                    CombatDefenderDomain(
                        id = defender,
                        kind = CombatTargetKind.PLAYER,
                        name = "defender",
                        lifeOrLoyaltyOrDefense = 20,
                    )
                ),
                edges = listOf(
                    CombatDamageEdgeDomain(
                        id = "edge",
                        sourceId = attacker,
                        targetId = defender,
                        direction = CombatDamageDirection.ATTACKER_TO_PLAYER,
                        amount = 2,
                        maximum = 2,
                        lethal = 0,
                        isTrampleDrain = false,
                        editableBy = EntityId("player"),
                    )
                ),
                coChooserId = null,
            ),
        )
        ChosenSemanticResponseV1.from(
            domain,
            CombatResolutionResponse("decision", listOf(DamageEdgeAmount("edge", 2))),
        )
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticResponseV1.from(
                domain,
                CombatResolutionResponse("decision", listOf(DamageEdgeAmount("edge", 1))),
            )
        }
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticResponseV1.from(
                domain,
                CombatResolutionResponse("decision", listOf(DamageEdgeAmount("unknown", 2))),
            )
        }
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

    test("an explicit V3 payment choice is retained and validated against its domain") {
        val environment = environment()
        val sourceDomain = actionDomain(environment)
        val payment = paymentDomain()
        val candidate = replaceJsonValue(
            candidateWithRequiredPayload(sourceDomain, listOf("paymentStrategy")),
            "paymentDomain",
            A3SemanticJson.strictJson.encodeToJsonElement(
                com.wingedsheep.gym.contract.PaymentDomainV5.serializer(),
                payment,
            ),
        )
        val domain = CompleteLegalDomainV1(
            kind = CompleteLegalDomainKind.ACTION_CANDIDATES,
            candidates = listOf(candidate),
        )
        val redBucket = InitialPoolBucketKeyV1.UnrestrictedPoolBucket(PaymentManaColor.RED)
        val blueBucket = InitialPoolBucketKeyV1.UnrestrictedPoolBucket(PaymentManaColor.BLUE)
        val validPlan = PaymentPlanV3(
            outerAllocation = listOf(
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(0, 0),
                    resource = ManaResourceRefV1.InitialPoolResource(redBucket),
                )
            )
        )
        val invalidPlan = validPlan.copy(
            outerAllocation = listOf(
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(0, 0),
                    resource = ManaResourceRefV1.InitialPoolResource(blueBucket),
                )
            )
        )

        val chosen = ChosenSemanticActionV1.from(
            domain,
            candidate,
            explicitPaymentPayload(validPlan),
        )
        chosen.canonicalJson().contains("ExplicitV3").shouldBeTrue()
        chosen.canonicalJson().contains("outerAllocation").shouldBeTrue()
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.from(domain, candidate, explicitPaymentPayload(invalidPlan))
        }
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
            val encoded = A3SemanticJson.strictJson.encodeToJsonElement(
                SemanticDecisionIdentityV1.serializer(),
                identity(SemanticReplayPrefixV1()),
            )
            A3SemanticJson.strictJson.decodeFromJsonElement(
                SemanticDecisionIdentityV1.serializer(),
                replaceJsonValue(
                    encoded.jsonObject,
                    "version",
                    JsonPrimitive(SEMANTIC_DECISION_IDENTITY_V1_VERSION + 1),
                ),
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
