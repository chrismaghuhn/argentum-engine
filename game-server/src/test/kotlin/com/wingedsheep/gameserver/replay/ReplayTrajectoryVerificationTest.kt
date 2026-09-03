package com.wingedsheep.gameserver.replay

import com.wingedsheep.gameserver.ScenarioTestBase
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.EpisodeClosureV1
import com.wingedsheep.gym.contract.CandidateDomainDigestV1
import com.wingedsheep.gym.contract.VerifiedReplayFrame
import com.wingedsheep.gym.contract.VerifiedReplayVerification
import com.wingedsheep.gym.contract.ReplayFidelity as VerifiedReplayFidelity
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.Deck
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * RED characterization for the A4 public replay cursor.
 *
 * [ReplayReconstructor.reconstructStateAt] proves only that a state can be reached. It does not
 * return the perspective-safe observation, complete legal domain, or domain digest that a trusted
 * replay-backed policy boundary needs.
 */
class ReplayTrajectoryVerificationTest : ScenarioTestBase() {

    private val paidCard = card("A4 Paid Spell") {
        manaCost = "{0}"
        typeLine = "Sorcery"
        oracleText = "You gain 1 life."
        spell { effect = Effects.GainLife(1) }
    }

    private fun replay(): CompactReplay = CompactReplay(
        version = CompactReplay.CURRENT_VERSION,
        gameId = "a4-red",
        players = listOf(
            ReplayPlayerInfo("p1", "Alice"),
            ReplayPlayerInfo("p2", "Bob"),
        ),
        startedAt = Instant.parse("2026-01-01T00:00:00Z").toString(),
        endedAt = Instant.parse("2026-01-01T00:01:00Z").toString(),
        winnerName = null,
        setup = ReplaySetup(
            seed = 7L,
            format = Format.Standard,
            attackMode = AttackMode.MULTIPLE,
            startingHandSize = 0,
            skipMulligans = true,
            players = listOf(
                ReplayPlayerSetup("p1", "Alice", Deck(cards = listOf("Forest"))),
                ReplayPlayerSetup("p2", "Bob", Deck(cards = listOf("Forest"))),
            ),
            seatRoster = emptyList(),
        ),
        actions = emptyList(),
    )

    private fun recordedReplay(): CompactReplay {
        val p1 = com.wingedsheep.sdk.model.EntityId.of("a4-player-1")
        val p2 = com.wingedsheep.sdk.model.EntityId.of("a4-player-2")
        val deck = Deck(cards = List(40) { "Forest" })
        val config = GameConfig(
            players = listOf(
                PlayerConfig("Alice", deck, playerId = p1),
                PlayerConfig("Bob", deck, playerId = p2),
            ),
            startingHandSize = 1,
            skipMulligans = true,
            startingPlayerIndex = 0,
            seed = 7L,
        )
        val environment = GameEnvironment.create(
            cardRegistry = cardRegistry,
        )
        environment.reset(config, maxSteps = 100)
        val actions = mutableListOf<com.wingedsheep.engine.core.GameAction>()
        repeat(40) {
            val action = environment.legalActions()
                .firstOrNull { it.action is com.wingedsheep.engine.core.PassPriority }
                ?.action
                ?: return@repeat
            actions += action
            environment.step(action)
        }

        val base = CompactReplay(
            version = CompactReplay.CURRENT_VERSION,
            gameId = "a4-recorded",
            players = listOf(
                ReplayPlayerInfo(p1.value, "Alice"),
                ReplayPlayerInfo(p2.value, "Bob"),
            ),
            startedAt = Instant.parse("2026-01-01T00:00:00Z").toString(),
            endedAt = Instant.parse("2026-01-01T00:01:00Z").toString(),
            winnerName = null,
            setup = ReplaySetup(
                seed = 7L,
                format = Format.Standard,
                attackMode = AttackMode.MULTIPLE,
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
                players = listOf(
                    ReplayPlayerSetup(p1.value, "Alice", deck),
                    ReplayPlayerSetup(p2.value, "Bob", deck),
                ),
                seatRoster = emptyList(),
            ),
            actions = actions,
        )
        val reconstructor = ReplayReconstructor(cardRegistry, null)
        val initial = reconstructor.reconstructStateAt(base, 0).shouldNotBeNull()
        val middle = reconstructor.reconstructStateAt(base, 20).shouldNotBeNull()
        val final = reconstructor.reconstructStateAt(base, actions.size).shouldNotBeNull()
        return base.copy(
            checkpoints = listOf(
                ReplayCheckpoint(0, ReplayFingerprint.of(initial, base.version)),
                ReplayCheckpoint(20, ReplayFingerprint.of(middle, base.version)),
                ReplayCheckpoint(actions.size, ReplayFingerprint.of(final, base.version)),
            )
        )
    }

    private fun source(replay: CompactReplay): GymReplayFrameSource = GymReplayFrameSource(
        replay = replay,
        cardRegistry = cardRegistry,
        tailClosure = EpisodeClosureV1.Interrupted(
            stepCount = replay.actions.size,
            reason = com.wingedsheep.gym.EpisodeInterruptionReason.HORIZON_REACHED,
        ),
    )

    private fun exactSource(replay: CompactReplay): GymReplayFrameSource = source(replay)

    private fun paidReplay(): CompactReplay {
        cardRegistry.register(paidCard)
        val p1 = com.wingedsheep.sdk.model.EntityId.of("a4-paid-player-1")
        val p2 = com.wingedsheep.sdk.model.EntityId.of("a4-paid-player-2")
        val deck = Deck(cards = List(40) { paidCard.name })
        val config = GameConfig(
            players = listOf(
                PlayerConfig("Alice", deck, playerId = p1),
                PlayerConfig("Bob", deck, playerId = p2),
            ),
            startingHandSize = 1,
            skipMulligans = true,
            startingPlayerIndex = 0,
            seed = 17L,
        )
        val environment = GameEnvironment.create(cardRegistry = cardRegistry)
        environment.reset(config, maxSteps = 20)
        val actions = mutableListOf<com.wingedsheep.engine.core.GameAction>()
        var cast: CastSpell? = null
        repeat(20) {
            if (cast != null) return@repeat
            val current = environment.legalActions()
            val castCandidate = current.firstOrNull { it.action is CastSpell }?.action as? CastSpell
            if (castCandidate != null) {
                cast = castCandidate
                actions += castCandidate
                environment.step(castCandidate)
                return@repeat
            }
            val pass = current.firstOrNull { it.action is PassPriority }?.action
                ?: error("Paid replay fixture lost priority before casting")
            actions += pass
            environment.step(pass)
        }
        checkNotNull(cast) { "Paid replay fixture never reached a cast boundary" }

        val base = CompactReplay(
            version = CompactReplay.CURRENT_VERSION,
            gameId = "a4-paid",
            players = listOf(
                ReplayPlayerInfo(p1.value, "Alice"),
                ReplayPlayerInfo(p2.value, "Bob"),
            ),
            startedAt = Instant.parse("2026-01-01T00:00:00Z").toString(),
            endedAt = Instant.parse("2026-01-01T00:01:00Z").toString(),
            winnerName = null,
            setup = ReplaySetup(
                seed = 17L,
                format = Format.Standard,
                attackMode = AttackMode.MULTIPLE,
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
                players = listOf(
                    ReplayPlayerSetup(p1.value, "Alice", deck),
                    ReplayPlayerSetup(p2.value, "Bob", deck),
                ),
                seatRoster = emptyList(),
            ),
            actions = actions,
        )
        val reconstructor = ReplayReconstructor(cardRegistry, null)
        val initial = reconstructor.reconstructStateAt(base, 0).shouldNotBeNull()
        val final = reconstructor.reconstructStateAt(base, actions.size).shouldNotBeNull()
        return base.copy(
            checkpoints = listOf(
                ReplayCheckpoint(0, ReplayFingerprint.of(initial, base.version)),
                ReplayCheckpoint(actions.size, ReplayFingerprint.of(final, base.version)),
            ),
        )
    }

    init {
        test("state reconstruction alone does not prove a complete public replay boundary") {
            val baseReplay = replay()
            val initialState = ReplayReconstructor(cardRegistry, null)
                .reconstructStateAt(baseReplay, 0)
                .shouldNotBeNull()
            val replay = baseReplay.copy(
                checkpoints = listOf(
                    ReplayCheckpoint(
                        afterActionCount = 0,
                        fingerprint = ReplayFingerprint.of(initialState, baseReplay.version),
                    )
                )
            )
            ReplayReconstructor(cardRegistry, null)
                .reconstructStateAt(replay, 0)
                .shouldNotBeNull()

            val verification = exactSource(replay).verify()

            verification.fidelity shouldBe VerifiedReplayFidelity.EXACT
        }

        test("a recorded action stream is verified against the public boundary before folding") {
            val verification = exactSource(recordedReplay()).verify()

            verification.fidelity shouldBe VerifiedReplayFidelity.EXACT
            verification.completeRangeVerified shouldBe true
            verification.frames.size shouldBe verification.replayActionCount + 1
            verification.closure shouldBe EpisodeClosureV1.Interrupted(
                stepCount = verification.replayActionCount,
                reason = com.wingedsheep.gym.EpisodeInterruptionReason.HORIZON_REACHED,
            )
        }

        test("an initial checkpoint mutation fails before publishing a frame prefix") {
            val replay = recordedReplay()
            val mutated = replay.copy(
                checkpoints = replay.checkpoints.map { checkpoint ->
                    if (checkpoint.afterActionCount == 0) {
                        checkpoint.copy(fingerprint = "0".repeat(64))
                    } else {
                        checkpoint
                    }
                }
            )

            val verification = source(mutated).verify()

            verification.fidelity shouldBe VerifiedReplayFidelity.DIVERGED
            verification.frames shouldBe emptyList()
            verification.initialCheckpointVerified shouldBe false
            verification.completeRangeVerified shouldBe false
        }

        test("a middle checkpoint mutation stops at the first affected action") {
            val replay = recordedReplay()
            val middleCount = replay.actions.size / 2
            val mutated = replay.copy(
                checkpoints = replay.checkpoints.map { checkpoint ->
                    if (checkpoint.afterActionCount == middleCount) {
                        checkpoint.copy(fingerprint = "0".repeat(64))
                    } else {
                        checkpoint
                    }
                }
            )

            val verification = source(mutated).verify()

            verification.fidelity shouldBe VerifiedReplayFidelity.DIVERGED
            verification.failureAtReplayActionIndex shouldBe middleCount - 1
            verification.frames.map { it.replayActionIndex } shouldBe (0 until middleCount).toList()
            verification.completeRangeVerified shouldBe false
        }

        test("omitting a required intermediate checkpoint is not exact") {
            val replay = recordedReplay()
            val withoutMiddle = replay.copy(
                checkpoints = replay.checkpoints.filterNot {
                    it.afterActionCount == replay.actions.size / 2
                }
            )

            val verification = source(withoutMiddle).verify()

            verification.fidelity shouldBe VerifiedReplayFidelity.UNVERIFIED
            verification.frames.size shouldBe replay.actions.size + 1
            verification.intermediateCheckpointsVerified shouldBe false
            verification.completeRangeVerified shouldBe false
        }

        test("removing the tail checkpoint cannot produce exact verification") {
            val replay = recordedReplay()
            val verification = source(replay.copy(checkpoints = replay.checkpoints.dropLast(1))).verify()

            verification.fidelity shouldBe VerifiedReplayFidelity.UNVERIFIED
            verification.tailCheckpointVerified shouldBe false
            verification.frames.size shouldBe replay.actions.size + 1
            verification.completeRangeVerified shouldBe false
        }

        test("mutating the tail checkpoint fails at the terminal replay boundary") {
            val replay = recordedReplay()
            val mutated = replay.copy(
                checkpoints = replay.checkpoints.map { checkpoint ->
                    if (checkpoint.afterActionCount == replay.actions.size) {
                        checkpoint.copy(fingerprint = "0".repeat(64))
                    } else {
                        checkpoint
                    }
                }
            )

            val verification = source(mutated).verify()

            verification.fidelity shouldBe VerifiedReplayFidelity.DIVERGED
            verification.failureAtReplayActionIndex shouldBe replay.actions.size - 1
            verification.completeRangeVerified shouldBe false
        }

        test("a changed replay action fails at its public boundary") {
            val replay = recordedReplay()
            val initialState = ReplayReconstructor(cardRegistry, null)
                .reconstructStateAt(replay, 0)
                .shouldNotBeNull()
            val actor = initialState.pendingDecision?.playerId
                ?: initialState.priorityPlayerId
                ?: error("Replay initial state has no actor")
            val cardId = initialState.getHand(actor).first()
            val mutated = replay.copy(
                actions = replay.actions.toMutableList().also { actions ->
                    actions[0] = PlayLand(actor, cardId)
                }
            )

            val verification = source(mutated).verify()

            verification.fidelity shouldBe VerifiedReplayFidelity.DIVERGED
            verification.failureAtReplayActionIndex shouldBe 0
            verification.completeRangeVerified shouldBe false
        }

        test("an unknown future replay version fails closed") {
            val verification = source(recordedReplay().copy(version = CompactReplay.CURRENT_VERSION + 1)).verify()

            verification.fidelity shouldBe VerifiedReplayFidelity.DIVERGED
            verification.frames shouldBe emptyList()
            verification.completeRangeVerified shouldBe false
        }

        test("unknown verified frame and verification versions fail closed") {
            val verification = exactSource(recordedReplay()).verify()
            val frame = verification.frames.first()

            shouldThrow<IllegalArgumentException> {
                frame.copy(version = frame.version + 1)
            }
            shouldThrow<IllegalArgumentException> {
                verification.copy(version = verification.version + 1)
            }
        }

        test("verification serialization excludes defensive implementation state") {
            val verification = exactSource(recordedReplay()).verify()
            val encoded = Json.encodeToString(VerifiedReplayVerification.serializer(), verification)
            val decoded = Json.decodeFromString(VerifiedReplayVerification.serializer(), encoded)

            encoded shouldNotContain "frameSnapshot"
            encoded shouldNotContain "gameState"
            encoded shouldNotContain "debugState"
            decoded shouldBe verification
        }

        test("repeating one replay produces semantically identical verification") {
            val replay = recordedReplay()

            val first = exactSource(replay).verify()
            val second = exactSource(replay).verify()

            first shouldBe second
        }

        test("a domain mutation changes the exact candidate-domain digest") {
            val frame = exactSource(recordedReplay()).verify().frames.first()
            val changedDomain = frame.domain.copy(candidates = emptyList())
            val changedFrame = frame.copy(
                domain = changedDomain,
                candidateDomainDigest = CandidateDomainDigestV1.from(changedDomain),
            )

            changedFrame.candidateDomainDigest shouldNotBe frame.candidateDomainDigest
            CandidateDomainDigestV1.from(changedFrame.domain) shouldBe changedFrame.candidateDomainDigest
        }

        test("mutating caller-owned verification frames cannot remain complete") {
            val verification = exactSource(recordedReplay()).verify()
            val mutableFrames = verification.frames.toMutableList()
            val copied = verification.copy(frames = mutableFrames)

            mutableFrames.clear()

            copied.completeRangeVerified shouldBe true
            copied.frames.size shouldBe verification.frames.size
        }

        test("a frame with a mismatching candidate-domain digest fails closed") {
            val frame = exactSource(recordedReplay()).verify().frames.first()

            shouldThrow<IllegalArgumentException> {
                VerifiedReplayFrame(
                    replayActionIndex = frame.replayActionIndex,
                    perspectivePlayerId = frame.perspectivePlayerId,
                    observation = frame.observation,
                    domain = frame.domain,
                    candidateDomainDigest = CandidateDomainDigestV1(
                        value = "0".repeat(64),
                    ),
                )
            }
        }

        test("verified frames retain the existing perspective-safe public projection") {
            val verification = exactSource(recordedReplay()).verify()

            verification.frames.forEach { frame ->
                val observation = frame.observation
                observation.zones
                    .filter { it.hidden && it.ownerId != observation.perspectivePlayerId }
                    .forEach { zone -> zone.cards shouldBe emptyList() }
                Json.encodeToString(VerifiedReplayFrame.serializer(), frame)
                    .also { encoded ->
                        encoded shouldNotContain "gameState"
                        encoded shouldNotContain "debugState"
                        encoded shouldNotContain "internalState"
                        encoded shouldNotContain "actionId"
                        encoded shouldNotContain "decisionId"
                        encoded shouldNotContain "sessionId"
                        encoded shouldNotContain "envId"
                    }
            }
        }

        test("a nonterminal replay tail rejects an incorrect terminal closure") {
            val replay = recordedReplay()
            val verification = GymReplayFrameSource(
                replay = replay,
                cardRegistry = cardRegistry,
                tailClosure = EpisodeClosureV1.GameTerminal(
                    stepCount = replay.actions.size,
                    winnerId = null,
                ),
            ).verify()

            verification.fidelity shouldBe VerifiedReplayFidelity.DIVERGED
            verification.completeRangeVerified shouldBe false
        }

        test("a replayed AutoPay action is rejected at the public payment boundary") {
            val replay = paidReplay()
            val submitted = replay.actions.filterIsInstance<CastSpell>().single()
            submitted.paymentStrategy shouldBe PaymentStrategy.AutoPay

            val verification = source(replay).verify()

            verification.fidelity shouldBe VerifiedReplayFidelity.DIVERGED
            verification.failureAtReplayActionIndex shouldBe replay.actions.indexOf(submitted)
        }
    }
}
