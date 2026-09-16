package com.wingedsheep.gameserver.policy

import com.wingedsheep.ai.ActionResponse
import com.wingedsheep.ai.engine.EngineAiPlayerController
import com.wingedsheep.ai.llm.BottomCardsInfo
import com.wingedsheep.ai.llm.CardSummary
import com.wingedsheep.ai.llm.MulliganInfo
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.mechanics.mana.FloatingManaProvenanceClassification
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.MulliganStateComponent
import com.wingedsheep.gameserver.curriculum.CurriculumDeckSourceLoader
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerSession
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeTypeOf
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import org.springframework.web.socket.WebSocketSession

/**
 * ARENA_ML_01 — first controlled real model-vs-engine smoke.
 *
 * Canonical configuration: ML_POLICY seat Akiri, Fearless Voyager vs ENGINE_AI seat Chevill, Bane
 * of Monsters, 1v1 Commander over the persisted exact legal 100-card curriculum decks (never
 * altered here). One explicit game seed, one explicit policy seed, one explicit starting player.
 *
 * Shape: normal [GameSession] creation → ML_POLICY authority on P1 → ENGINE_AI authority on P2 →
 * normal mulligan → normal London bottoming → normal priority/decisions → terminal GameState or an
 * explicit bounded stop. No public Arena surface, no second GameSession loop: the harness drives the
 * production seams ([PolicySeatRuntime.decide] for the ML seat; the production
 * [EngineAiPlayerController] through the `*FromAiController` session methods for the engine seat).
 *
 * Fail-closed rules: the harness contains zero fallback paths — no Engine AI, random, first
 * candidate, AutoPass, AutoPay, implicit keep, or heuristic choice ever acts for the ML seat. Any
 * [PolicySeatDecisionResult.Rejected], any engine-controller failure, or any safety bound stops the
 * smoke immediately with a classification. A truncated game is never reported as a result.
 *
 * Operational smoke bounds (generous for a full 40-life Commander game; semantics untouched):
 * wall-clock 30 minutes, 600 ML policy decisions, 2500 total authoritative actions.
 *
 * Known characterization (ARENA_ML_01, 2026-09-16, narrowed per exact-SHA review): with game
 * seed 20260916 the exercised game deterministically reaches decision #71 (ML-seat upkeep,
 * Akiri's fourth turn — authoritative turnNumber 7) where the observation contains the trusted
 * [DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED] signal. At that exact state, Shadowspear's {1}
 * activation was enumerated as a legal, affordable candidate, but the trusted observation path
 * did not publish a complete PaymentDomainV5 and emitted the diagnostic, so
 * [LivePolicySourceAdapter] failed closed. The provenance state of Akiri's mana pool at that
 * boundary is captured and pinned in [SmokeReport.assertCharacterizedSmokeOutcome]
 * (BOUNDARY_POOL); the minimal RED characterization of that exact pool shape lives in
 * docs/ml/arena-ml-01-payment-domain-01-characterization.md
 * (ARENA_ML_01_PAYMENT_DOMAIN_01). Per the task contract the fail-closed outcome is
 * characterized here, never routed around: the primary test asserts the fail-closed outcome
 * (contract held, no fallback, worker clean) and the exact recorded boundary.
 */
class ArenaMl01RealModelVsEngineSmokeTest : FunSpec({
    test("REAL CUDA smoke: accepted C1_06 model controls Akiri vs Engine-AI Chevill; fail-closed characterization")
        .config(enabled = REAL_WORKER_AVAILABLE, timeout = 35.minutes) {
            val report = runRealSmoke()
            val rendered = report.format()
            println(rendered)
            report.assertCharacterizedSmokeOutcome(rendered)
        }

    test("smoke seat configuration keeps ML and Engine-AI authority exclusive") {
        val game = GameSession(cardRegistry = CardRegistry())
        game.addPlayer(PlayerSession(ws("arena-ml-01-a"), P1, "Akiri"), mapOf("Plains" to 60))
        game.addPlayer(PlayerSession(ws("arena-ml-01-b"), P2, "Chevill"), mapOf("Plains" to 60))
        game.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, POLICY_SEED))
        game.setControllerAuthority(P2, ControllerAuthorityV1.engineAi(1))

        game.getControllerAuthority(P1)?.isMlPolicy shouldBe true
        game.getControllerAuthority(P2)?.isMlPolicy shouldBe false

        // The ML seat is unreachable through every competing origin.
        game.executeActionFromController(P1, PassPriority(P1), ControllerKindV1.HUMAN)
            .shouldBeTypeOf<GameSession.ActionResult.Failure>()
        game.executeActionFromController(P1, PassPriority(P1), ControllerKindV1.ENGINE_AI)
            .shouldBeTypeOf<GameSession.ActionResult.Failure>()
        game.executeActionFromController(P1, PassPriority(P1), ControllerKindV1.LEGACY_AI)
            .shouldBeTypeOf<GameSession.ActionResult.Failure>()
        game.executeActionFromAiController(P1, PassPriority(P1))
            .shouldBeTypeOf<GameSession.ActionResult.Failure>()
        game.keepHandFromAiController(P1)
            .shouldBeTypeOf<GameSession.MulliganActionResult.Failure>()

        // The Engine-AI seat is unreachable through the ML capture path.
        io.kotest.assertions.throwables.shouldThrow<PolicySeatFailure> {
            game.captureLivePolicyDecision(P2)
        }.code shouldBe PolicySeatFailureCode.CONTROLLER_AUTHORITY_INVALID
    }
}) {
    companion object {
        private val P1 = EntityId("arena-ml-01-policy")
        private val P2 = EntityId("arena-ml-01-engine")

        // Canonical ARENA_ML_01 setup. Recorded verbatim in the smoke report.
        private const val GAME_SEED = 20260916L
        private const val POLICY_SEED = 20260901L
        private const val STARTING_PLAYER_INDEX = 0

        private const val AKIRI_PATH = "docs/ml/curriculum/akiri-v0.1.txt"
        private const val CHEVILL_PATH = "docs/ml/curriculum/chevill-v0.1.txt"
        private const val PRESET_IDENTITY = "argentum-mtg-ml-akiri-chevill-curriculum@v1"

        // Accepted C1_06 checkpoint identities (ml/live_policy/profile.py C1_07BPolicyProfile).
        private const val EXPECTED_CHECKPOINT_ID =
            "f4b191d99734af66a5643c988ce3c2f2198a2957be24e2a717287006a43124c5"
        private const val EXPECTED_WEIGHT_DIGEST =
            "02027b495f609a268b2d6d169250a66cdaab5f8658c4794d1e2669b484ea0168"
        private const val EXPECTED_MANIFEST_DIGEST =
            "973231cc16f8de28f618889b94cabe6ac12da67485a246207db16c8c902ed9a3"
        private const val EXPECTED_MODEL_CONFIG_DIGEST =
            "542b74694061b07c8adc397e27ea3a57b71edaaa33af24b05b99099d95d3c966"
        private const val EXPECTED_TRAINING_RUN_IDENTITY =
            "e1ef9daddcfb4d2444c800a3be2674123962f6be068feec6d6af6f1a9ba6dd64"

        // Operational smoke bounds (semantics untouched; a fired bound truncates, never completes).
        private const val WALL_CLOCK_LIMIT_MS = 30 * 60 * 1000L
        private const val MAX_ML_DECISIONS = 600
        private const val MAX_TOTAL_ACTIONS = 2500

        /**
         * Recorded deterministic fail-closed boundary (game seed 20260916): ML-seat upkeep
         * decision #71 (Akiri's fourth turn, authoritative turnNumber 7) — Shadowspear's paid {1}
         * activation was enumerated as a legal, affordable candidate, but the trusted observation
         * path did not publish a complete PaymentDomainV5 and emitted PAYMENT_DOMAIN_UNSUPPORTED
         * (lower-level cause characterized in ARENA_ML_01_PAYMENT_DOMAIN_01; see the class kdoc).
         * The recorded context continues with the JVM-side legal menu; this prefix pins the
         * semantic boundary, not the review-only menu text.
         */
        private const val KNOWN_BOUNDARY_CONTEXT =
            "decision #71 pregame=false pending=- phase=BEGINNING step=UPKEEP legal=5 " +
                "message=live observation contains unsupported authoritative diagnostics: " +
                "[PAYMENT_DOMAIN_UNSUPPORTED]"

        private val checkpointDirectory = Path.of(
            System.getenv("ARGENTUM_C1_06_CHECKPOINT_DIR")
                ?: Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "argentum-c1-06-gpu-smoke-943338abbaf47f289cfe606acd50caf0a2b15ef5",
                ).toString(),
        )
        private val pythonExecutable = System.getenv("ARGENTUM_ML_PYTHON")
            ?: "C:\\Python313\\python.exe"
        private val PYTHON_AVAILABLE = Files.isRegularFile(Path.of(pythonExecutable))
        private val REAL_WORKER_AVAILABLE =
            PYTHON_AVAILABLE && Files.isDirectory(checkpointDirectory)

        private fun ws(id: String): WebSocketSession = mockk<WebSocketSession>(relaxed = true).also {
            every { it.id } returns id
        }

        private fun sha256Hex(bytes: ByteArray): String = MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

        /** Best-effort runtime probe; never fails the smoke (worker HEALTH is authoritative). */
        private fun probePython(executable: String, script: String): String = try {
            val process = ProcessBuilder(executable, "-c", script)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(120, TimeUnit.SECONDS)
            if (!finished || process.exitValue() != 0) "UNAVAILABLE"
            else process.inputStream.bufferedReader().readText().trim().ifBlank { "UNAVAILABLE" }
        } catch (_: Exception) {
            "UNAVAILABLE"
        }

        private fun fullCorpusRegistry(): CardRegistry = CardRegistry().apply {
            MtgSetCatalog.all.forEach { set ->
                register(set.cards)
                register(set.basicLands)
            }
        }

        private fun ServerMessage.MulliganDecision.toMulliganInfo() = MulliganInfo(
            hand = hand,
            mulliganCount = mulliganCount,
            cardsToPutOnBottom = cardsToPutOnBottom,
            cards = cards.mapValues { (_, v) -> v.toCardSummary() },
            isOnThePlay = isOnThePlay,
        )

        private fun ServerMessage.ChooseBottomCards.toBottomCardsInfo() = BottomCardsInfo(
            hand = hand,
            cardsToPutOnBottom = cardsToPutOnBottom,
            cards = cards.mapValues { (_, v) -> v.toCardSummary() },
        )

        private fun ServerMessage.MulliganCardInfo.toCardSummary() = CardSummary(
            name = name,
            manaCost = manaCost,
            typeLine = typeLine,
            imageUri = imageUri,
            power = power,
            toughness = toughness,
            oracleText = oracleText,
        )

        private data class MlDecisionTrace(
            val index: Int,
            val pregame: Boolean,
            val pendingType: String,
            val priorityLegalCount: Int,
            val phase: String,
            val step: String,
            val ordinal: Int,
            val actionType: String,
            val cursorBefore: ULong,
            val cursorAfter: ULong,
            val actionsBefore: Int,
            val actionsAfter: Int,
        )

        private data class SmokeOutcome(
            val terminal: Boolean,
            val winner: String?,
            val gameOverReason: String?,
            val truncated: Boolean,
            val truncationReason: String?,
            val mlPregameDecisions: Int,
            val mlGameplayDecisions: Int,
            val engineDecisions: Int,
            val totalAuthoritativeActions: Int,
            val staleRejections: Int,
            val workerFailures: Int,
            val unsupportedCount: Int,
            val rejectionCode: String?,
            val rejectionContext: String?,
            val boundaryPool: String?,
            val mlTraces: List<MlDecisionTrace>,
            val finalPolicyCursor: ULong?,
            val wallClockMs: Long,
        )

        private data class SmokeReport(
            val outcome: SmokeOutcome,
            val akiriDigest: String,
            val chevillDigest: String,
            val weightDigest: String,
            val manifestDigest: String,
            val pythonVersion: String,
            val torchVersion: String,
            val cudaRuntime: String,
            val gpuName: String,
        ) {
            fun format(): String = buildString {
                appendLine("ARENA_ML_01 SMOKE REPORT")
                appendLine("BASE_SHA=dff9438a49e5359bb870ba37c343aa86e20a4c52")
                appendLine("BRANCH=chris/arena-ml-01-real-model-vs-engine-smoke-20260916")
                appendLine("ML_DECK=Akiri, Fearless Voyager ($AKIRI_PATH digest=$akiriDigest)")
                appendLine("ENGINE_AI_DECK=Chevill, Bane of Monsters ($CHEVILL_PATH digest=$chevillDigest)")
                appendLine("PRESET_IDENTITY=$PRESET_IDENTITY")
                appendLine("CHECKPOINT_ID=$EXPECTED_CHECKPOINT_ID")
                appendLine("WEIGHT_DIGEST=$weightDigest (expected $EXPECTED_WEIGHT_DIGEST)")
                appendLine("MANIFEST_DIGEST=$manifestDigest (expected $EXPECTED_MANIFEST_DIGEST)")
                appendLine("MODEL_CONFIG_DIGEST=$EXPECTED_MODEL_CONFIG_DIGEST (worker-validated)")
                appendLine("TRAINING_RUN_IDENTITY=$EXPECTED_TRAINING_RUN_IDENTITY")
                appendLine("PYTHON_VERSION=$pythonVersion")
                appendLine("TORCH_VERSION=$torchVersion")
                appendLine("CUDA_RUNTIME=$cudaRuntime")
                appendLine("GPU=$gpuName")
                appendLine("DEVICE=cuda:0 (worker-enforced, no CPU path)")
                appendLine("CPU_FALLBACK=NO")
                appendLine("MODEL_EVAL=true (worker-enforced)")
                appendLine("DETERMINISTIC_ALGORITHMS=enabled (worker-enforced)")
                appendLine("GAME_SEED=$GAME_SEED")
                appendLine("POLICY_SEED=$POLICY_SEED")
                appendLine("STARTING_PLAYER_INDEX=$STARTING_PLAYER_INDEX (P1 Akiri on the play)")
                // Pregame coverage is derived strictly from the recorded pregame ML action types
                // (KeepHand / TakeMulligan / BottomCards), never inferred from pregame decision
                // counts: a first-pull keep never exercises London bottoming.
                val pregameActions = outcome.mlTraces.filter { it.pregame }.map { it.actionType }
                appendLine("PREGAME_ML_ACTION_TYPES=${pregameActions.joinToString(",")}")
                appendLine(
                    "MULLIGAN_MODEL_CONTROLLED=" +
                        if (pregameActions.any { it == "KeepHand" || it == "TakeMulligan" }) "YES" else "NO",
                )
                appendLine(
                    "BOTTOMING_MODEL_CONTROLLED=" +
                        if (pregameActions.any { it == "BottomCards" }) "YES" else "NOT_EXERCISED",
                )
                appendLine("FIRST_GAMEPLAY_MODEL_DECISION_REACHED=${outcome.mlGameplayDecisions > 0}")
                appendLine("ML_PREGAME_DECISIONS=${outcome.mlPregameDecisions}")
                appendLine("ML_GAMEPLAY_DECISIONS=${outcome.mlGameplayDecisions}")
                appendLine("ML_TOTAL_DECISIONS=${outcome.mlPregameDecisions + outcome.mlGameplayDecisions}")
                appendLine("ENGINE_AI_DECISIONS=${outcome.engineDecisions}")
                appendLine("AUTHORITATIVE_ACTIONS=${outcome.totalAuthoritativeActions}")
                appendLine("TERMINAL_GAME=${outcome.terminal}")
                appendLine("WINNER=${outcome.winner ?: "-"}")
                appendLine("GAME_OVER_REASON=${outcome.gameOverReason ?: "-"}")
                appendLine("SMOKE_TRUNCATED=${outcome.truncated}")
                appendLine("TRUNCATION_REASON=${outcome.truncationReason ?: "-"}")
                appendLine("ZERO_UNSUPPORTED=${outcome.unsupportedCount == 0}")
                appendLine("UNSUPPORTED_COUNT=${outcome.unsupportedCount}")
                // Status semantics per ARENA_ML_01 exact-SHA review: the fail-closed truncation is
                // a successful characterization, not a completed model-vs-engine game. These are
                // computed task statuses; independent-review verdicts (P1/P2/P3,
                // CODE_REVIEW_PASS, FINAL_ACCEPTANCE_PASS) are never emitted here.
                appendLine(
                    "EXECUTION_PLUMBING_PASS=" +
                        if (outcome.workerFailures == 0 &&
                            outcome.staleRejections == 0 &&
                            outcome.mlPregameDecisions + outcome.mlGameplayDecisions > 0
                        ) "YES" else "NO",
                )
                appendLine(
                    "REAL_CUDA_CHARACTERIZATION_PASS=" +
                        if (outcome.unsupportedCount > 0 &&
                            outcome.rejectionCode == "UNSUPPORTED_STRUCTURED_DECISION" &&
                            outcome.terminal.not() &&
                            outcome.workerFailures == 0 &&
                            outcome.staleRejections == 0
                        ) "YES" else "NO",
                )
                appendLine("COMPLETE_GAME_SMOKE=${if (outcome.terminal) "COMPLETED" else "BLOCKED"}")
                appendLine("FALLBACK_COUNT=0")
                appendLine("STALE_REJECTION_COUNT=${outcome.staleRejections}")
                appendLine("WORKER_FAILURE_COUNT=${outcome.workerFailures}")
                appendLine("REJECTION_CODE=${outcome.rejectionCode ?: "-"}")
                appendLine("REJECTION_CONTEXT=${outcome.rejectionContext ?: "-"}")
                appendLine("BOUNDARY_POOL=${outcome.boundaryPool ?: "-"}")
                appendLine("FINAL_POLICY_CURSOR=${outcome.finalPolicyCursor}")
                appendLine("WALL_CLOCK_MS=${outcome.wallClockMs}")
                appendLine("POLICY_RNG_COMMIT_SEMANTICS=cursor advanced only on accepted execution; " +
                    "accepted=${outcome.mlPregameDecisions + outcome.mlGameplayDecisions} " +
                    "finalCursor=${outcome.finalPolicyCursor}")
                appendLine("PRIVACY_REVIEW=model requests carry model-facing features + candidate " +
                    "ordinals only; no raw GameState, bindings, digests, hidden info, or EntityIds")
                appendLine("AUTHORITY_REVIEW=ML seat ML_POLICY-exclusive; engine seat ENGINE_AI-only; " +
                    "cross-origin attempts fail closed (see exclusivity test)")
                appendLine("RUNTIME_CLEANUP_REVIEW=worker closed in finally; post-close decide rejected")
                appendLine("ML_DECISION_TRACE (first 20):")
                outcome.mlTraces.take(20).forEach { trace ->
                    appendLine("  #${trace.index} pregame=${trace.pregame} " +
                        "pending=${trace.pendingType} legal=${trace.priorityLegalCount} " +
                        "${trace.phase}/${trace.step} ordinal=${trace.ordinal} " +
                        "action=${trace.actionType} cursor=${trace.cursorBefore}->${trace.cursorAfter} " +
                        "actions=${trace.actionsBefore}->${trace.actionsAfter}")
                }
            }

            /**
             * ARENA_ML_01 contract assertions: prove the model really played through the accepted
             * stack and that the stack held its fail-closed contract at the known boundary. At the
             * exact deterministic decision #71 state, Shadowspear's {1} activation was enumerated
             * legal/affordable while the trusted observation path did not publish a complete
             * PaymentDomainV5 and emitted PAYMENT_DOMAIN_UNSUPPORTED (lower-level root cause
             * uncharacterized here; a separate RED characterization task owns that isolation).
             * Execution plumbing is proven; the complete-game smoke is blocked by that boundary,
             * asserted here only as the recorded, exact fail-closed outcome.
             */
            fun assertCharacterizedSmokeOutcome(rendered: String) {
                // The real runtime stack: accepted checkpoint digests and a probed CUDA
                // environment (exact probed versions are recorded in the report, not pinned
                // here, because the probes are best-effort environment introspection).
                weightDigest shouldBe EXPECTED_WEIGHT_DIGEST
                manifestDigest shouldBe EXPECTED_MANIFEST_DIGEST
                pythonVersion shouldNotBe "UNAVAILABLE"
                torchVersion shouldNotBe "UNAVAILABLE"
                cudaRuntime shouldNotBe "UNAVAILABLE"
                gpuName shouldNotBe "UNAVAILABLE"
                gpuName shouldNotBe "NO_CUDA"

                // The model genuinely drove the seat through pregame and real gameplay.
                (outcome.mlPregameDecisions + outcome.mlGameplayDecisions) shouldBeGreaterThan 0
                outcome.mlGameplayDecisions shouldBeGreaterThan 0
                outcome.mlPregameDecisions shouldBeGreaterThan 0
                outcome.engineDecisions shouldBeGreaterThan 0
                (outcome.totalAuthoritativeActions) shouldBeGreaterThan 0
                outcome.mlTraces.first().actionType shouldBe "KeepHand"

                // The fail-closed contract held at the known boundary: no fallback, no silent
                // substitution, no RNG advance, no worker failure, no stale acceptance.
                outcome.workerFailures shouldBe 0
                outcome.staleRejections shouldBe 0
                outcome.unsupportedCount shouldBe 1
                outcome.terminal shouldBe false
                outcome.truncated shouldBe true
                outcome.rejectionCode shouldBe "UNSUPPORTED_STRUCTURED_DECISION"
                outcome.rejectionContext.shouldStartWith(KNOWN_BOUNDARY_CONTEXT)
                outcome.finalPolicyCursor shouldBe 0uL

                // Durable link from the real ARENA boundary to the characterized root cause
                // (ARENA_ML_01_PAYMENT_DOMAIN_01): Akiri's exact mana-pool provenance state at
                // the fail-closed rejection, captured from the authoritative GameState in the
                // rejection branch. The subtype-only shape (subtype provenance present, source
                // provenance consumed by the legacy proportional spend seam) is the input the
                // minimal RED characterization proves is classified Ambiguous and refused by
                // PaymentDomainV5's initial-pool admission.
                val boundaryPool = checkNotNull(outcome.boundaryPool) {
                    "fail-closed boundary recorded no ManaPoolComponent evidence"
                }
                boundaryPool.shouldStartWith(
                    "turn=7 step=UPKEEP white=1 blue=0 black=0 red=0 green=0 colorless=0 " +
                        "bySubtype={Plains=1} bySource={} byFloatingBucket={} completeness=INCOMPLETE ",
                )
                boundaryPool.shouldContain(
                    "classification=Ambiguous(reason=source and subtype provenance must both identify the pool)",
                )
                rendered.shouldContain("BOUNDARY_POOL=$boundaryPool")

                // Pregame evidence must name the actual exercised decisions: this deterministic
                // run kept on its first pull, so bottoming was never exercised.
                rendered.shouldContain("PREGAME_ML_ACTION_TYPES=KeepHand")
                rendered.shouldContain("MULLIGAN_MODEL_CONTROLLED=YES")
                rendered.shouldContain("BOTTOMING_MODEL_CONTROLLED=NOT_EXERCISED")

                // Status semantics per the exact-SHA review: characterization success, blocked
                // complete game. Never self-assign review verdicts.
                rendered.shouldContain("EXECUTION_PLUMBING_PASS=YES")
                rendered.shouldContain("REAL_CUDA_CHARACTERIZATION_PASS=YES")
                rendered.shouldContain("COMPLETE_GAME_SMOKE=BLOCKED")
                rendered.shouldContain("TERMINAL_GAME=false")
                rendered.shouldContain("ZERO_UNSUPPORTED=false")
                rendered.shouldContain("UNSUPPORTED_COUNT=1")
                rendered.shouldContain("FALLBACK_COUNT=0")
                rendered.shouldContain("WORKER_FAILURE_COUNT=0")
                rendered.shouldContain("STALE_REJECTION_COUNT=0")
                rendered.shouldContain("TRUNCATION_REASON=ML decision rejected closed: " +
                    "UNSUPPORTED_STRUCTURED_DECISION")
                // No self-assigned review verdicts in the generated artifact.
                listOf("P1=", "P2=", "P3=", "CODE_REVIEW_PASS", "FINAL_ACCEPTANCE_PASS").forEach { field ->
                    rendered.lines().filter { it.startsWith(field) } shouldBe emptyList<String>()
                }
            }
        }

        private fun runRealSmoke(): SmokeReport {
            val startedAtMs = System.currentTimeMillis()

            // Independent JVM-side cross-check: the accepted digests must match byte-for-byte
            // before the worker even starts (the worker re-validates and fails closed itself).
            val weightDigest = sha256Hex(Files.readAllBytes(checkpointDirectory.resolve("weights.safetensors")))
            val manifestDigest = sha256Hex(Files.readAllBytes(checkpointDirectory.resolve("manifest.json")))
            check(weightDigest == EXPECTED_WEIGHT_DIGEST) {
                "Accepted weight digest mismatch: $weightDigest"
            }
            check(manifestDigest == EXPECTED_MANIFEST_DIGEST) {
                "Accepted manifest digest mismatch: $manifestDigest"
            }

            val registry = fullCorpusRegistry()
            val loader = CurriculumDeckSourceLoader()
            val akiri = loader.load(AKIRI_PATH)
            val chevill = loader.load(CHEVILL_PATH)
            check(akiri.commander == "Akiri, Fearless Voyager") { "ML deck is not Akiri: ${akiri.commander}" }
            check(chevill.commander == "Chevill, Bane of Monsters") {
                "Engine deck is not Chevill: ${chevill.commander}"
            }

            val game = GameSession(cardRegistry = registry)
            game.engineFormat = Format.Commander()
            game.addPlayer(
                PlayerSession(ws("arena-ml-01-policy"), P1, "Akiri"),
                akiri.libraryDeckList(),
                commanderCardName = akiri.commander,
            )
            game.addPlayer(
                PlayerSession(ws("arena-ml-01-engine"), P2, "Chevill"),
                chevill.libraryDeckList(),
                commanderCardName = chevill.commander,
            )
            game.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, POLICY_SEED))
            game.setControllerAuthority(P2, ControllerAuthorityV1.engineAi(1))
            game.startGame(gameSeed = GAME_SEED, startingPlayerIndex = STARTING_PLAYER_INDEX)

            val engineController = EngineAiPlayerController(
                cardRegistry = registry,
                playerId = P2,
                gameStateProvider = { game.getStateSnapshot() },
            )

            var mlPregameDecisions = 0
            var mlGameplayDecisions = 0
            var engineDecisions = 0
            var staleRejections = 0
            var workerFailures = 0
            var unsupportedCount = 0
            var rejectionCode: String? = null
            var rejectionContext: String? = null
            var boundaryPool: String? = null
            var truncationReason: String? = null
            val mlTraces = mutableListOf<MlDecisionTrace>()

            val worker = LocalPythonPolicyWorker.start(
                PolicySeatRuntimeConfiguration(
                    pythonExecutable = pythonExecutable,
                    checkpointDirectory = checkpointDirectory,
                ),
            )
            val runtime = PolicySeatRuntime(game, P1, worker)
            try {
                var decisionIndex = 0
                while (!game.isGameOver()) {
                    if (System.currentTimeMillis() - startedAtMs > WALL_CLOCK_LIMIT_MS) {
                        truncationReason = "wall-clock limit ${WALL_CLOCK_LIMIT_MS}ms fired"
                        break
                    }
                    if (mlPregameDecisions + mlGameplayDecisions >= MAX_ML_DECISIONS) {
                        truncationReason = "ML decision bound $MAX_ML_DECISIONS fired"
                        break
                    }
                    if (game.getRecordedActions().size >= MAX_TOTAL_ACTIONS) {
                        truncationReason = "total action bound $MAX_TOTAL_ACTIONS fired"
                        break
                    }

                    if (game.policySeatToAct() == P1) {
                        decisionIndex++
                        val pregame = !game.allMulligansComplete
                        val preState = game.getStateForTesting()
                        val pendingType = preState?.pendingDecision?.let { it::class.simpleName } ?: "-"
                        val legalCount = game.getLegalActions(P1).size
                        val phase = preState?.phase?.name ?: "-"
                        val step = preState?.step?.name ?: "-"
                        val cursorBefore = game.getPolicySeatState(P1)?.cursor
                            ?: error("ML seat lost its PolicySeatStateV1")
                        val actionsBefore = game.getRecordedActions().size

                        when (val result = runtime.decide()) {
                            is PolicySeatDecisionResult.Accepted -> {
                                val cursorAfter = game.getPolicySeatState(P1)?.cursor
                                    ?: error("ML seat lost its PolicySeatStateV1 after accept")
                                val actionsAfter = game.getRecordedActions().size
                                check(actionsAfter > actionsBefore) {
                                    "Accepted ML decision recorded no authoritative action"
                                }
                                val actionType = game.getRecordedActions().last()::class.simpleName ?: "-"
                                mlTraces += MlDecisionTrace(
                                    index = decisionIndex,
                                    pregame = pregame,
                                    pendingType = pendingType,
                                    priorityLegalCount = legalCount,
                                    phase = phase,
                                    step = step,
                                    ordinal = result.selectedSourceBindingOrdinal,
                                    actionType = actionType,
                                    cursorBefore = cursorBefore,
                                    cursorAfter = cursorAfter,
                                    actionsBefore = actionsBefore,
                                    actionsAfter = actionsAfter,
                                )
                                if (pregame) mlPregameDecisions++ else mlGameplayDecisions++
                            }
                            is PolicySeatDecisionResult.Rejected -> {
                                rejectionCode = result.failure.code.name
                                // JVM-side review diagnostics only: the seat-owner-visible legal
                                // menu at the failed boundary. Never part of any model request.
                                val legalMenu = game.getLegalActions(P1)
                                    .joinToString(" | ") { "${it.actionType}: ${it.description.take(90)}" }
                                rejectionContext =
                                    "decision #$decisionIndex pregame=$pregame pending=$pendingType " +
                                    "phase=$phase step=$step legal=$legalCount " +
                                    "message=${result.failure.message} menu=[$legalMenu]"
                                // Durable review evidence (ARENA_ML_01_PAYMENT_DOMAIN_01 remediation):
                                // the acting ML seat's authoritative pool provenance at the exact
                                // rejection state, including the Rules-owned classification the
                                // trusted payment-domain admission consults. JVM-side only;
                                // never part of any model request.
                                boundaryPool = preState?.getEntity(P1)?.get<ManaPoolComponent>()?.let { pool ->
                                    "turn=${preState.turnNumber} step=${preState.step.name} " +
                                        "white=${pool.white} blue=${pool.blue} black=${pool.black} " +
                                        "red=${pool.red} green=${pool.green} colorless=${pool.colorless} " +
                                        "bySubtype=${pool.manaBySubtype} bySource=${pool.manaBySource} " +
                                        "byFloatingBucket=${pool.manaByFloatingBucket} " +
                                        "completeness=${pool.manaProvenanceCompleteness} " +
                                        "classification=${FloatingManaProvenanceClassification.classify(pool)}"
                                }
                                when (result.failure.code) {
                                    PolicySeatFailureCode.STALE_INFERENCE -> staleRejections++
                                    PolicySeatFailureCode.WORKER_STARTUP_FAILURE,
                                    PolicySeatFailureCode.WORKER_PROTOCOL_FAILURE,
                                    PolicySeatFailureCode.WORKER_TIMEOUT,
                                    PolicySeatFailureCode.WORKER_CRASH,
                                    -> workerFailures++
                                    PolicySeatFailureCode.UNSUPPORTED_MULLIGAN_DECISION,
                                    PolicySeatFailureCode.UNSUPPORTED_STRUCTURED_DECISION,
                                    -> unsupportedCount++
                                    else -> workerFailures++
                                }
                                truncationReason = "ML decision rejected closed: $rejectionCode"
                                break
                            }
                        }
                    } else {
                        driveEngineSeat(game, engineController)
                        engineDecisions++
                    }
                }
            } finally {
                runtime.close()
                check(runtime.decide() is PolicySeatDecisionResult.Rejected) {
                    "PolicySeatRuntime did not close its worker lane"
                }
            }

            val wallClockMs = System.currentTimeMillis() - startedAtMs
            val outcome = SmokeOutcome(
                terminal = game.isGameOver(),
                winner = game.getWinnerId()?.value,
                gameOverReason = game.getGameOverReason()?.name,
                truncated = !game.isGameOver(),
                truncationReason = truncationReason,
                mlPregameDecisions = mlPregameDecisions,
                mlGameplayDecisions = mlGameplayDecisions,
                engineDecisions = engineDecisions,
                totalAuthoritativeActions = game.getRecordedActions().size,
                staleRejections = staleRejections,
                workerFailures = workerFailures,
                unsupportedCount = unsupportedCount,
                rejectionCode = rejectionCode,
                rejectionContext = rejectionContext,
                boundaryPool = boundaryPool,
                mlTraces = mlTraces.toList(),
                finalPolicyCursor = game.getPolicySeatState(P1)?.cursor,
                wallClockMs = wallClockMs,
            )
            return SmokeReport(
                outcome = outcome,
                akiriDigest = akiri.sourceDigest,
                chevillDigest = chevill.sourceDigest,
                weightDigest = weightDigest,
                manifestDigest = manifestDigest,
                pythonVersion = probePython(pythonExecutable, "import sys; print(sys.version.split()[0])"),
                torchVersion = probePython(
                    pythonExecutable,
                    "import sys; sys.path.insert(0, 'ml/src'); import torch; print(torch.__version__)",
                ),
                cudaRuntime = probePython(
                    pythonExecutable,
                    "import sys; sys.path.insert(0, 'ml/src'); import torch; print(torch.version.cuda)",
                ),
                gpuName = probePython(
                    pythonExecutable,
                    "import sys; sys.path.insert(0, 'ml/src'); import torch; " +
                        "print(torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'NO_CUDA')",
                ),
            )
        }

        /**
         * Drive the ENGINE_AI seat one step through the production controller and the existing
         * AI-only session path. Never touches the ML seat; contains no fallback — any failure
         * throws and truncates the smoke via the caller's classification.
         */
        private fun driveEngineSeat(game: GameSession, controller: EngineAiPlayerController) {
            val state = game.getStateForTesting() ?: error("Engine seat acts with no game state")
            if (!game.allMulligansComplete) {
                val mulligan = state.getEntity(P2)?.get<MulliganStateComponent>()
                    ?: error("Engine seat has no mulligan state during pregame")
                if (!mulligan.hasKept) {
                    val keep = controller.decideMulligan(game.getMulliganDecision(P2).toMulliganInfo())
                    if (keep) {
                        when (val kept = game.keepHandFromAiController(P2)) {
                            is GameSession.MulliganActionResult.Success -> Unit
                            is GameSession.MulliganActionResult.NeedsBottomCards ->
                                bottomEngineCards(game, controller)
                            is GameSession.MulliganActionResult.Failure ->
                                error("Engine-AI keep rejected: ${kept.reason}")
                        }
                    } else {
                        val taken = game.takeMulliganFromAiController(P2)
                        check(taken is GameSession.MulliganActionResult.Success) {
                            "Engine-AI mulligan rejected: ${(taken as? GameSession.MulliganActionResult.Failure)?.reason}"
                        }
                    }
                } else {
                    bottomEngineCards(game, controller)
                }
                return
            }

            val pending = state.pendingDecision?.takeIf { state.actorFor(it.playerId) == P2 }
            val acting = pending?.let { state.actorFor(it.playerId) } ?: state.priorityPlayerId?.let {
                state.actorFor(it)
            }
            check(acting == P2) {
                "Engine driver reached with no Engine-AI decision to make (acting=$acting)"
            }
            val clientState = game.getClientState(P2)
                ?: error("Engine seat has no client state for gameplay decision")
            val response = controller.chooseAction(
                clientState,
                game.getLegalActions(P2),
                pending,
                emptyList(),
            )
            val result = when (response) {
                is ActionResponse.SubmitAction ->
                    game.executeActionFromAiController(P2, response.action)
                is ActionResponse.SubmitDecision ->
                    game.executeActionFromAiController(P2, SubmitDecision(P2, response.response))
            }
            check(result is GameSession.ActionResult.Success ||
                result is GameSession.ActionResult.PausedForDecision) {
                "Engine-AI action rejected: ${(result as? GameSession.ActionResult.Failure)?.reason}"
            }
        }

        private fun bottomEngineCards(game: GameSession, controller: EngineAiPlayerController) {
            val message = game.getChooseBottomCardsMessage(P2)
                ?: error("Engine seat owes bottom cards but has no bottom-cards message")
            val bottom = controller.chooseBottomCards(message.toBottomCardsInfo())
            val result = game.chooseBottomCardsFromAiController(P2, bottom)
            check(result is GameSession.MulliganActionResult.Success) {
                "Engine-AI bottom cards rejected: ${(result as? GameSession.MulliganActionResult.Failure)?.reason}"
            }
        }
    }
}
