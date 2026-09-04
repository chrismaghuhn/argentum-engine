package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.gym.contract.ChosenSemanticActionV1
import com.wingedsheep.gym.contract.ChosenSemanticResponseV1
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.contract.CompleteLegalDomainKind
import com.wingedsheep.gym.contract.CompleteLegalDomainV1
import com.wingedsheep.gym.contract.DecisionShape
import com.wingedsheep.gym.contract.PendingDecisionKind
import com.wingedsheep.gym.contract.PlayerObservationV1
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TargetRequirementDomain
import com.wingedsheep.gym.contract.TargetsDomain
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Characterization of the accepted A3 prefix bytes before adding the linear seam. */
class SemanticReplayPrefixAccumulatorTest : FunSpec({

    fun action(label: String): SemanticReplayInputV1 = SemanticReplayInputV1(
        kind = SemanticReplayInputKind.ACTION,
        semanticValue = buildJsonObject {
            put("type", "chosen-action")
            put("candidate", buildJsonObject {
                put("affordable", true)
                put("kind", label)
            })
            put("choicePayload", buildJsonObject {})
        },
    )

    fun response(choice: Boolean): SemanticReplayInputV1 = SemanticReplayInputV1(
        kind = SemanticReplayInputKind.RESPONSE,
        semanticValue = buildJsonObject {
            put("type", "chosen-response")
            put("response", buildJsonObject {
                put("type", "YesNoResponse")
                put("choice", choice)
            })
        },
    )

    fun structuredResponse(): SemanticReplayInputV1 = SemanticReplayInputV1(
        kind = SemanticReplayInputKind.RESPONSE,
        semanticValue = buildJsonObject {
            put("type", "chosen-response")
            put("response", buildJsonObject {
                put("type", "TargetsResponse")
                put("selectedTargets", buildJsonObject {
                    put("0", buildJsonArray { add(JsonPrimitive("target-a")) })
                })
            })
        },
    )

    fun acceptedStructuredResponse(): SemanticReplayInputV1 {
        val target = EntityId("target-a")
        val domain = CompleteLegalDomainV1(
            kind = CompleteLegalDomainKind.STRUCTURED_DECISION,
            decisionKind = PendingDecisionKind.CHOOSE_TARGETS,
            shape = DecisionShape(minSelections = 1, maxSelections = 1),
            structuredDomain = TargetsDomain(
                requirements = listOf(
                    TargetRequirementDomain(
                        index = 0,
                        description = "target",
                        minTargets = 1,
                        maxTargets = 1,
                        candidates = listOf(target),
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
                    ),
                ),
                canCancel = false,
            ),
        )
        return SemanticReplayInputV1.response(
            ChosenSemanticResponseV1.from(
                domain,
                TargetsResponse("routing-only", mapOf(0 to listOf(target))),
            ),
        )
    }

    fun legacyDigest(inputs: List<SemanticReplayInputV1>): SemanticReplayPrefixDigestV1 =
        SemanticReplayPrefixV1(inputs = inputs).digest()

    fun environment(): GameEnvironment {
        val registry = CardRegistry().also {
            it.register(PortalSet.cards)
            it.register(PortalSet.basicLands)
        }
        val environment = GameEnvironment.create(registry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 20)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 2,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 70L,
            )
        )
        return environment
    }

    fun observation(environment: GameEnvironment): TrainingObservation =
        ObservationBuilder(
            cardRegistry = CardRegistry().also {
                it.register(PortalSet.cards)
                it.register(PortalSet.basicLands)
            },
        ).build(
            environment.state,
            environment.playerIds.first(),
            environment.legalActions(),
        ).observation as TrainingObservation

    fun identityFixture(): Triple<String, PlayerObservationV1, CompleteLegalDomainV1> {
        val environment = environment()
        val source = observation(environment)
        val playerObservation = PlayerObservationV1.from(source)
        val domain = CompleteLegalDomainV1.from(source)
        return Triple("a".repeat(64), playerObservation, domain)
    }

    test("characterizes the exact canonical bytes for representative prefixes") {
        val prefixes = listOf(
            SemanticReplayPrefixV1(),
            SemanticReplayPrefixV1(inputs = listOf(action("PASS"))),
            SemanticReplayPrefixV1(inputs = listOf(action("PASS"), response(true))),
            SemanticReplayPrefixV1(
                inputs = listOf(action("PASS"), response(true), structuredResponse()),
            ),
        )

        prefixes[0].canonicalJson() shouldBe
            "{\"inputs\":[],\"schemaIdentity\":\"argentum-trajectory-semantic-replay-prefix@v1\",\"version\":1}"
        prefixes.map { it.canonicalJson() } shouldBe listOf(
            """{"inputs":[],"schemaIdentity":"argentum-trajectory-semantic-replay-prefix@v1","version":1}""",
            """{"inputs":[{"kind":"ACTION","schemaIdentity":"argentum-trajectory-semantic-replay-input@v1","semanticValue":{"candidate":{"affordable":true,"kind":"PASS"},"choicePayload":{},"type":"chosen-action"},"version":1}],"schemaIdentity":"argentum-trajectory-semantic-replay-prefix@v1","version":1}""",
            """{"inputs":[{"kind":"ACTION","schemaIdentity":"argentum-trajectory-semantic-replay-input@v1","semanticValue":{"candidate":{"affordable":true,"kind":"PASS"},"choicePayload":{},"type":"chosen-action"},"version":1},{"kind":"RESPONSE","schemaIdentity":"argentum-trajectory-semantic-replay-input@v1","semanticValue":{"response":{"choice":true,"type":"YesNoResponse"},"type":"chosen-response"},"version":1}],"schemaIdentity":"argentum-trajectory-semantic-replay-prefix@v1","version":1}""",
            """{"inputs":[{"kind":"ACTION","schemaIdentity":"argentum-trajectory-semantic-replay-input@v1","semanticValue":{"candidate":{"affordable":true,"kind":"PASS"},"choicePayload":{},"type":"chosen-action"},"version":1},{"kind":"RESPONSE","schemaIdentity":"argentum-trajectory-semantic-replay-input@v1","semanticValue":{"response":{"choice":true,"type":"YesNoResponse"},"type":"chosen-response"},"version":1},{"kind":"RESPONSE","schemaIdentity":"argentum-trajectory-semantic-replay-input@v1","semanticValue":{"response":{"selectedTargets":{"0":["target-a"]},"type":"TargetsResponse"},"type":"chosen-response"},"version":1}],"schemaIdentity":"argentum-trajectory-semantic-replay-prefix@v1","version":1}""",
        )
        prefixes.forEach { prefix ->
            println("A35_CANONICAL_PREFIX_${prefix.inputs.size}=${prefix.canonicalJson()}")
        }
    }

    test("A35-01 empty accumulator identity has legacy digest parity") {
        val (semanticEpisodeId, observation, domain) = identityFixture()
        val accumulator = SemanticReplayPrefixAccumulatorV1()

        val identity = accumulator.semanticDecisionIdentity(
            semanticEpisodeId = semanticEpisodeId,
            observation = observation,
            domain = domain,
        )
        identity.replayActionIndex shouldBe 0
        identity.replayPrefixDigest shouldBe legacyDigest(emptyList()).value
    }

    test("A35-02 single action accumulator identity has legacy digest parity") {
        val (semanticEpisodeId, observation, domain) = identityFixture()
        val inputs = listOf(action("PASS"))
        val accumulator = SemanticReplayPrefixAccumulatorV1()
        accumulator.append(inputs.single())

        val identity = accumulator.semanticDecisionIdentity(
            semanticEpisodeId = semanticEpisodeId,
            observation = observation,
            domain = domain,
        )
        identity.replayActionIndex shouldBe 1
        identity.replayPrefixDigest shouldBe legacyDigest(inputs).value
    }

    test("A35-03 single response accumulator identity has legacy digest parity") {
        val (semanticEpisodeId, observation, domain) = identityFixture()
        val inputs = listOf(response(true))
        val accumulator = SemanticReplayPrefixAccumulatorV1()
        accumulator.append(inputs.single())

        val identity = accumulator.semanticDecisionIdentity(
            semanticEpisodeId = semanticEpisodeId,
            observation = observation,
            domain = domain,
        )
        identity.replayActionIndex shouldBe 1
        identity.replayPrefixDigest shouldBe legacyDigest(inputs).value
    }

    test("A35-04 accepted structured response input crosses the existing A3 validator") {
        val (semanticEpisodeId, observation, domain) = identityFixture()
        val inputs = listOf(acceptedStructuredResponse())
        val accumulator = SemanticReplayPrefixAccumulatorV1()
        accumulator.append(inputs.single())

        accumulator.semanticDecisionIdentity(
            semanticEpisodeId = semanticEpisodeId,
            observation = observation,
            domain = domain,
        ).replayPrefixDigest shouldBe legacyDigest(inputs).value
    }

    test("A35-04 mixed ordered prefixes retain legacy digest parity after every append") {
        val inputs = listOf(
            action("PASS"),
            response(true),
            action("CAST"),
            structuredResponse(),
            response(false),
        )
        val (semanticEpisodeId, observation, domain) = identityFixture()
        val accumulator = SemanticReplayPrefixAccumulatorV1()

        inputs.forEachIndexed { index, input ->
            accumulator.append(input)
            accumulator.semanticDecisionIdentity(
                semanticEpisodeId = semanticEpisodeId,
                observation = observation,
                domain = domain,
            ).replayPrefixDigest shouldBe legacyDigest(inputs.take(index + 1)).value
        }
    }

    test("A35-05 identity queries do not consume or mutate the live accumulator") {
        val (semanticEpisodeId, observation, domain) = identityFixture()
        val firstInput = action("FIRST")
        val secondInput = response(true)
        val thirdInput = structuredResponse()
        val accumulator = SemanticReplayPrefixAccumulatorV1()

        accumulator.append(firstInput)
        val first = accumulator.semanticDecisionIdentity(
            semanticEpisodeId = semanticEpisodeId,
            observation = observation,
            domain = domain,
        )
        val repeated = accumulator.semanticDecisionIdentity(
            semanticEpisodeId = semanticEpisodeId,
            observation = observation,
            domain = domain,
        )
        first shouldBe repeated

        accumulator.append(secondInput)
        val second = accumulator.semanticDecisionIdentity(
            semanticEpisodeId = semanticEpisodeId,
            observation = observation,
            domain = domain,
        )
        second.replayPrefixDigest shouldBe legacyDigest(listOf(firstInput, secondInput)).value

        accumulator.append(thirdInput)
        val third = accumulator.semanticDecisionIdentity(
            semanticEpisodeId = semanticEpisodeId,
            observation = observation,
            domain = domain,
        )
        third.replayPrefixDigest shouldBe legacyDigest(
            listOf(firstInput, secondInput, thirdInput),
        ).value
        first.replayPrefixDigest shouldBe legacyDigest(listOf(firstInput)).value
    }

    test("A35-06 input ordering remains semantic") {
        val (semanticEpisodeId, observation, domain) = identityFixture()
        val first = action("A")
        val second = response(true)
        val forward = SemanticReplayPrefixAccumulatorV1().also {
            it.append(first)
            it.append(second)
        }.semanticDecisionIdentity(
            semanticEpisodeId = semanticEpisodeId,
            observation = observation,
            domain = domain,
        )
        val reverse = SemanticReplayPrefixAccumulatorV1().also {
            it.append(second)
            it.append(first)
        }.semanticDecisionIdentity(
            semanticEpisodeId = semanticEpisodeId,
            observation = observation,
            domain = domain,
        )

        forward.replayPrefixDigest shouldBe legacyDigest(listOf(first, second)).value
        reverse.replayPrefixDigest shouldBe legacyDigest(listOf(second, first)).value
        forward.replayPrefixDigest shouldNotBe reverse.replayPrefixDigest
    }

    test("A35-07 the accumulator produces the exact legacy semantic decision identity") {
        val (semanticEpisodeId, observation, domain) = identityFixture()
        val chosen = ChosenSemanticActionV1.from(domain, domain.candidates.first { candidate ->
            candidate["affordable"] == JsonPrimitive(true)
        })
        val input = SemanticReplayInputV1.action(chosen)
        val legacyPrefix = SemanticReplayPrefixV1(inputs = listOf(input))
        val accumulator = SemanticReplayPrefixAccumulatorV1()
        accumulator.append(input)

        val legacy = SemanticDecisionIdentityV1.from(
            semanticEpisodeId = semanticEpisodeId,
            prefix = legacyPrefix,
            replayActionIndex = 1,
            observation = observation,
            domain = domain,
        )
        val incremental = accumulator.semanticDecisionIdentity(
            semanticEpisodeId = semanticEpisodeId,
            replayActionIndex = 1,
            observation = observation,
            domain = domain,
        )

        incremental shouldBe legacy
        incremental.semanticDecisionId() shouldBe legacy.semanticDecisionId()
    }

    test("A35-08 parity holds over a deterministic 256-input prefix") {
        val (semanticEpisodeId, observation, domain) = identityFixture()
        val inputs = buildList {
            repeat(256) { index ->
                add(if (index % 3 == 0) response(index % 2 == 0) else action("ACTION-$index"))
            }
        }
        val accumulator = SemanticReplayPrefixAccumulatorV1()

        inputs.forEachIndexed { index, input ->
            accumulator.append(input)
            accumulator.semanticDecisionIdentity(
                semanticEpisodeId = semanticEpisodeId,
                observation = observation,
                domain = domain,
            ).replayPrefixDigest shouldBe legacyDigest(inputs.take(index + 1)).value
        }
    }

    test("A35-09 accumulator count must match replay action index") {
        val (semanticEpisodeId, observation, domain) = identityFixture()
        val accumulator = SemanticReplayPrefixAccumulatorV1()
        accumulator.append(action("PASS"))

        shouldThrow<IllegalArgumentException> {
            accumulator.semanticDecisionIdentity(
                semanticEpisodeId = semanticEpisodeId,
                replayActionIndex = 2,
                observation = observation,
                domain = domain,
            )
        }
    }

    test("A35-10 the supported SHA-256 provider copies state without consuming it") {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("prefix".toByteArray())
        val copy = digest.clone() as MessageDigest

        copy.digest() shouldBe digest.clone().let { (it as MessageDigest).digest() }
        digest.update("-suffix".toByteArray())
        digest.digest() shouldBe MessageDigest.getInstance("SHA-256")
            .digest("prefix-suffix".toByteArray())
    }

    test("future or transport-bearing semantic inputs remain rejected before accumulation") {
        shouldThrow<IllegalArgumentException> {
            SemanticReplayInputV1(
                version = SEMANTIC_REPLAY_INPUT_V1_VERSION + 1,
                kind = SemanticReplayInputKind.ACTION,
                semanticValue = action("FUTURE").semanticValue,
            )
        }
        shouldThrow<IllegalArgumentException> {
            SemanticReplayInputV1(
                kind = SemanticReplayInputKind.ACTION,
                semanticValue = buildJsonObject {
                    put("type", "chosen-action")
                    put("candidate", buildJsonObject { put("actionId", "runtime") })
                    put("choicePayload", buildJsonObject {})
                },
            )
        }
    }

    test("the linear accumulator identity path has no digest-state injection constructor") {
        SemanticReplayPrefixAccumulatorV1::class.java.declaredConstructors
            .filterNot { it.isSynthetic }
            .none { it.parameterTypes.isNotEmpty() } shouldBe true
    }

    test("the linear identity path is obtained directly from a real accumulator") {
        val (semanticEpisodeId, observation, domain) = identityFixture()
        val accumulator = SemanticReplayPrefixAccumulatorV1()

        accumulator.semanticDecisionIdentity(
            semanticEpisodeId = semanticEpisodeId,
            replayActionIndex = 0,
            observation = observation,
            domain = domain,
        ).replayActionIndex shouldBe 0
    }

    test("append work only processes the new canonical input and identity queries do not append to live state") {
        val (semanticEpisodeId, observation, domain) = identityFixture()
        val accumulator = SemanticReplayPrefixAccumulatorV1()
        val inputs = buildList {
            repeat(256) { index ->
                add(if (index % 2 == 0) action("ACTION-$index") else response(index % 3 == 0))
            }
        }

        inputs.forEachIndexed { index, input ->
            val beforeAppend = accumulator.processedByteCount
            accumulator.append(input)
            val appendedBytes = accumulator.processedByteCount - beforeAppend
            val expectedBytes = input.canonicalJson().toByteArray(StandardCharsets.UTF_8).size +
                if (index == 0) 0 else 1
            appendedBytes shouldBe expectedBytes

            val beforeIdentity = accumulator.processedByteCount
            accumulator.semanticDecisionIdentity(
                semanticEpisodeId = semanticEpisodeId,
                replayActionIndex = index + 1,
                observation = observation,
                domain = domain,
            )
            accumulator.semanticDecisionIdentity(
                semanticEpisodeId = semanticEpisodeId,
                replayActionIndex = index + 1,
                observation = observation,
                domain = domain,
            )
            accumulator.processedByteCount shouldBe beforeIdentity
        }
    }
})
