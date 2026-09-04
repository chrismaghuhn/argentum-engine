package com.wingedsheep.gym.trainer.trajectory

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

    test("A35-01 empty snapshot has legacy digest parity") {
        val accumulator = SemanticReplayPrefixAccumulatorV1()

        accumulator.snapshot().inputCount shouldBe 0
        accumulator.snapshot().digest shouldBe legacyDigest(emptyList())
    }

    test("A35-02 single action snapshot has legacy digest parity") {
        val inputs = listOf(action("PASS"))
        val accumulator = SemanticReplayPrefixAccumulatorV1()
        accumulator.append(inputs.single())

        accumulator.snapshot().inputCount shouldBe 1
        accumulator.snapshot().digest shouldBe legacyDigest(inputs)
    }

    test("A35-03 single response snapshot has legacy digest parity") {
        val inputs = listOf(response(true))
        val accumulator = SemanticReplayPrefixAccumulatorV1()
        accumulator.append(inputs.single())

        accumulator.snapshot().inputCount shouldBe 1
        accumulator.snapshot().digest shouldBe legacyDigest(inputs)
    }

    test("A35-04 accepted structured response input crosses the existing A3 validator") {
        val inputs = listOf(acceptedStructuredResponse())
        val accumulator = SemanticReplayPrefixAccumulatorV1()
        accumulator.append(inputs.single())

        accumulator.snapshot().digest shouldBe legacyDigest(inputs)
    }

    test("A35-04 mixed ordered prefixes retain legacy digest parity after every append") {
        val inputs = listOf(
            action("PASS"),
            response(true),
            action("CAST"),
            structuredResponse(),
            response(false),
        )
        val accumulator = SemanticReplayPrefixAccumulatorV1()

        inputs.forEachIndexed { index, input ->
            accumulator.append(input)
            val snapshot = accumulator.snapshot()
            snapshot.inputCount shouldBe index + 1
            snapshot.digest shouldBe legacyDigest(inputs.take(index + 1))
        }
    }

    test("A35-05 snapshots do not consume or mutate the live accumulator") {
        val firstInput = action("FIRST")
        val secondInput = response(true)
        val thirdInput = structuredResponse()
        val accumulator = SemanticReplayPrefixAccumulatorV1()

        accumulator.append(firstInput)
        val first = accumulator.snapshot()
        val repeated = accumulator.snapshot()
        first shouldBe repeated

        accumulator.append(secondInput)
        val second = accumulator.snapshot()
        second.digest shouldBe legacyDigest(listOf(firstInput, secondInput))

        accumulator.append(thirdInput)
        val third = accumulator.snapshot()
        third.digest shouldBe legacyDigest(listOf(firstInput, secondInput, thirdInput))
        first.digest shouldBe legacyDigest(listOf(firstInput))
    }

    test("A35-06 input ordering remains semantic") {
        val first = action("A")
        val second = response(true)
        val forward = SemanticReplayPrefixAccumulatorV1().also {
            it.append(first)
            it.append(second)
        }.snapshot()
        val reverse = SemanticReplayPrefixAccumulatorV1().also {
            it.append(second)
            it.append(first)
        }.snapshot()

        forward.digest shouldBe legacyDigest(listOf(first, second))
        reverse.digest shouldBe legacyDigest(listOf(second, first))
        forward.digest shouldNotBe reverse.digest
    }

    test("A35-07 incremental prefix snapshots produce the exact legacy semantic decision identity") {
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
        val incremental = SemanticDecisionIdentityV1.from(
            semanticEpisodeId = semanticEpisodeId,
            prefixSnapshot = accumulator.snapshot(),
            replayActionIndex = 1,
            observation = observation,
            domain = domain,
        )

        incremental shouldBe legacy
        incremental.semanticDecisionId() shouldBe legacy.semanticDecisionId()
    }

    test("A35-08 parity holds over a deterministic 256-input prefix") {
        val inputs = buildList {
            repeat(256) { index ->
                add(if (index % 3 == 0) response(index % 2 == 0) else action("ACTION-$index"))
            }
        }
        val accumulator = SemanticReplayPrefixAccumulatorV1()

        inputs.forEachIndexed { index, input ->
            accumulator.append(input)
            accumulator.snapshot().digest shouldBe legacyDigest(inputs.take(index + 1))
        }
    }

    test("A35-09 snapshot count must match replay action index") {
        val (semanticEpisodeId, observation, domain) = identityFixture()
        val accumulator = SemanticReplayPrefixAccumulatorV1()
        accumulator.append(action("PASS"))

        shouldThrow<IllegalArgumentException> {
            SemanticDecisionIdentityV1.from(
                semanticEpisodeId = semanticEpisodeId,
                prefixSnapshot = accumulator.snapshot(),
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

    test("future or malformed digest snapshots fail closed") {
        val emptyDigest = SemanticReplayPrefixV1().digest()

        shouldThrow<IllegalArgumentException> {
            SemanticReplayPrefixDigestSnapshotV1(
                version = SEMANTIC_REPLAY_PREFIX_DIGEST_SNAPSHOT_V1_VERSION + 1,
                inputCount = 0,
                digest = emptyDigest,
            )
        }
        shouldThrow<IllegalArgumentException> {
            SemanticReplayPrefixDigestSnapshotV1(
                inputCount = -1,
                digest = emptyDigest,
            )
        }
    }

    test("append work only processes the new canonical input and snapshot work does not append to live state") {
        val state = CountingDigestState()
        val accumulator = SemanticReplayPrefixAccumulatorV1(state)
        val inputs = buildList {
            repeat(256) { index ->
                add(if (index % 2 == 0) action("ACTION-$index") else response(index % 3 == 0))
            }
        }

        inputs.forEachIndexed { index, input ->
            val beforeAppend = state.liveUpdatedByteCount
            accumulator.append(input)
            val appendedBytes = state.liveUpdatedByteCount - beforeAppend
            val expectedBytes = input.canonicalJson().toByteArray().size + if (index == 0) 0 else 1
            appendedBytes shouldBe expectedBytes

            val beforeSnapshot = state.liveUpdatedByteCount
            accumulator.snapshot()
            accumulator.snapshot()
            state.liveUpdatedByteCount shouldBe beforeSnapshot
        }
    }
})

private class CountingDigestState : SemanticReplayDigestState {
    private val digest = MessageDigest.getInstance("SHA-256")

    var liveUpdatedByteCount: Int = 0
        private set

    override fun update(bytes: ByteArray) {
        digest.update(bytes)
        liveUpdatedByteCount += bytes.size
    }

    override fun snapshotDigest(closingBytes: ByteArray): ByteArray {
        val copy = digest.clone() as MessageDigest
        copy.update(closingBytes)
        return copy.digest()
    }
}
