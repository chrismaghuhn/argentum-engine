package com.wingedsheep.gameserver.replay.verification

import com.wingedsheep.gym.EpisodeClosureV1
import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.CandidateDomainDigestV1
import com.wingedsheep.gym.contract.CompleteLegalDomainKind
import com.wingedsheep.gym.contract.CompleteLegalDomainV1
import com.wingedsheep.gym.contract.ManaPoolView
import com.wingedsheep.gym.contract.PlayerObservationV1
import com.wingedsheep.gym.contract.PlayerView
import com.wingedsheep.gym.contract.ReplayContentIdentityV1
import com.wingedsheep.gym.contract.ReplayFidelity
import com.wingedsheep.gym.contract.ReplayTrajectoryBindingV1
import com.wingedsheep.gym.contract.ReplayVerificationBindingV1
import com.wingedsheep.gym.contract.ReplayChosenInputBindingV1
import com.wingedsheep.gym.contract.VerifiedReplayFrame
import com.wingedsheep.gym.contract.VerifiedReplayVerification
import com.wingedsheep.gameserver.curriculum.CurriculumAiTournamentPreset
import com.wingedsheep.gameserver.curriculum.CurriculumDeckSourceLoader
import com.wingedsheep.gym.trainer.actor.OfflineReplayFailureCodeV1
import com.wingedsheep.gym.trainer.actor.OfflineReplayVerificationResultV1
import com.wingedsheep.gym.trainer.trajectory.PolicyProvenanceV1
import com.wingedsheep.gym.trainer.actor.WorkAssignmentV1
import com.wingedsheep.gym.trainer.actor.WorkloadJobV1
import com.wingedsheep.gym.trainer.actor.WorkloadPlanV1
import com.wingedsheep.gym.trainer.trajectory.CompactReplayLinkV1
import com.wingedsheep.gym.trainer.trajectory.EnvironmentIdentityV1
import com.wingedsheep.gym.trainer.trajectory.EpisodeMetadataV1
import com.wingedsheep.gym.trainer.trajectory.RosterSeatV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1
import com.wingedsheep.gameserver.replay.CompactReplay
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Focused production contract tests for the process-isolated offline replay verifier (KA06 _02).
 *
 * Heavy end-to-end episodes (real producer + real publication envelope + real fresh-worker
 * reconstruction) live in the gym module's `kaggleActor06CharacterizationTest` gate, which is the
 * conformance oracle for [TransportedReplayReconstructorV1]. This suite pins the production
 * verifier's own trust seams: same-revision authentication ordering, request/result
 * cross-binding, typed fail-closed failure modes, and the worker protocol.
 */
class ProductionOfflineReplayVerifierTest : FunSpec({

    test("worker protocol round-trips a canonical request and result") {
        val trajectory = fabricatedTrajectory()
        val request = VerifierWorkerRequestV1.of(trajectory)
        val encodedRequest = VerifierWorkerProtocolV1Json.encodeRequest(request)
        val decodedRequest = VerifierWorkerProtocolV1Json.decodeRequest(encodedRequest)
        encodedRequest shouldBe VerifierWorkerProtocolV1Json.encodeRequest(decodedRequest)
        decodedRequest.claimantTrajectory() shouldBe trajectory

        val binding = fabricatedBinding()
        val result = VerifierWorkerResultV1(
            status = VerifierWorkerStatusV1.VERIFIED,
            verifiedResult = VerifiedWorkerVerificationV1(
                verifiedTrajectoryId = trajectory.trajectoryId,
                verifiedSemanticEpisodeId = trajectory.semanticEpisodeId,
                verifiedReplayContentIdentity =
                    trajectory.episodeMetadata.compactReplayLink.replayContentIdentity,
                verifiedReplayActionCount = 0,
                replayTrajectoryBinding = binding,
            ),
        )
        val decodedResult = VerifierWorkerProtocolV1Json.decodeResult(
            VerifierWorkerProtocolV1Json.encodeResult(result),
        )
        decodedResult shouldBe result
        decodedResult.toVerificationResult()
            .shouldBeInstanceOf<OfflineReplayVerificationResultV1.Verified>()

        val failure = VerifierWorkerResultV1(
            status = VerifierWorkerStatusV1.UNAVAILABLE,
            failureCode = OfflineReplayFailureCodeV1.SOURCE_REVISION_MISMATCH,
        )
        val decodedFailure = VerifierWorkerProtocolV1Json.decodeResult(
            VerifierWorkerProtocolV1Json.encodeResult(failure),
        )
        decodedFailure.toVerificationResult()
            .shouldBeInstanceOf<OfflineReplayVerificationResultV1.Unavailable>()
    }

    test("worker maps typed reconstruction failures onto the admission failure taxonomy") {
        TransportedReplayVerifierWorkerMain.mapReconstructionFailure(
            TransportedReplayReconstructionException(
                TransportedReplayReconstructionFailure.SOURCE_AUTHENTICATION_FAILED,
                "head mismatch",
                sourceBootstrapFailure =
                    com.wingedsheep.gym.trainer.actor.SourceBootstrapFailureCodeV1.HEAD_MISMATCH,
            ),
        ) shouldBe OfflineReplayFailureCodeV1.SOURCE_REVISION_MISMATCH

        TransportedReplayVerifierWorkerMain.mapReconstructionFailure(
            TransportedReplayReconstructionException(
                TransportedReplayReconstructionFailure.SOURCE_AUTHENTICATION_FAILED,
                "dirty tree",
                sourceBootstrapFailure =
                    com.wingedsheep.gym.trainer.actor.SourceBootstrapFailureCodeV1.TRACKED_SOURCE_DIRTY,
            ),
        ) shouldBe OfflineReplayFailureCodeV1.SOURCE_REVISION_UNVERIFIED

        TransportedReplayVerifierWorkerMain.mapReconstructionFailure(
            TransportedReplayReconstructionException(
                TransportedReplayReconstructionFailure.SEMANTIC_REBIND_FAILED,
                "not a member",
            ),
        ) shouldBe OfflineReplayFailureCodeV1.SEMANTIC_REBIND_FAILED

        TransportedReplayVerifierWorkerMain.mapReconstructionFailure(
            TransportedReplayReconstructionException(
                TransportedReplayReconstructionFailure.TRAJECTORY_IDENTITY_MISMATCH,
                "identity",
            ),
        ) shouldBe OfflineReplayFailureCodeV1.TRAJECTORY_IDENTITY_MISMATCH
    }

    test("request binding: a worker result for a different trajectory id is rejected") {
        val verifier = CrossBindingProbeVerifier(testLaunchOverrides())
        val trajectory = fabricatedTrajectory()

        val forged = VerifierWorkerResultV1(
            status = VerifierWorkerStatusV1.VERIFIED,
            verifiedResult = VerifiedWorkerVerificationV1(
                verifiedTrajectoryId = "a".repeat(64),
                verifiedSemanticEpisodeId = trajectory.semanticEpisodeId,
                verifiedReplayContentIdentity =
                    trajectory.episodeMetadata.compactReplayLink.replayContentIdentity,
                verifiedReplayActionCount = 3,
                replayTrajectoryBinding = fabricatedBinding(),
            ),
        )
        val rejected = verifier.crossBindingCheck(trajectory, forged)
        rejected.shouldBeInstanceOf<OfflineReplayVerificationResultV1.Unavailable>()
    }

    test("request binding: every identity field participates") {
        val verifier = CrossBindingProbeVerifier(testLaunchOverrides())
        val trajectory = fabricatedTrajectory()
        val link = trajectory.episodeMetadata.compactReplayLink
        // The positive pole needs an EXACT, internally consistent binding now that the seam
        // validates the nested binding itself (P2 hardening).
        val binding = fabricatedBinding(fidelity = ReplayFidelity.EXACT, replayActionCount = link.replayActionCount)

        fun forgedResult(
            trajectoryId: String = trajectory.trajectoryId,
            semanticEpisodeId: String = trajectory.semanticEpisodeId,
            contentIdentity: String = link.replayContentIdentity,
            actionCount: Int = link.replayActionCount,
        ) = VerifierWorkerResultV1(
            status = VerifierWorkerStatusV1.VERIFIED,
            verifiedResult = VerifiedWorkerVerificationV1(
                verifiedTrajectoryId = trajectoryId,
                verifiedSemanticEpisodeId = semanticEpisodeId,
                verifiedReplayContentIdentity = contentIdentity,
                verifiedReplayActionCount = actionCount,
                replayTrajectoryBinding = binding,
            ),
        )

        verifier.crossBindingCheck(trajectory, forgedResult())
            .shouldBeInstanceOf<OfflineReplayVerificationResultV1.Verified>()
        verifier.crossBindingCheck(trajectory, forgedResult(semanticEpisodeId = "b".repeat(64)))
            .shouldBeInstanceOf<OfflineReplayVerificationResultV1.Unavailable>()
        verifier.crossBindingCheck(trajectory, forgedResult(contentIdentity = "c".repeat(64)))
            .shouldBeInstanceOf<OfflineReplayVerificationResultV1.Unavailable>()
        verifier.crossBindingCheck(trajectory, forgedResult(actionCount = link.replayActionCount + 1))
            .shouldBeInstanceOf<OfflineReplayVerificationResultV1.Unavailable>()
    }

    test("worker exits nonzero without a response file when its output path is unwritable") {
        val crashVerifier = CrashingWorkerVerifier(testLaunchOverrides())
        val exit = crashVerifier.launchAndAwait(fabricatedTrajectory())
        exit shouldBe 1
    }

    test("bounded timeout produces a typed VERIFIER_TIMEOUT, never VERIFIED") {
        val trajectory = fabricatedTrajectory()
        val verifier = TimedOutWorkerVerifier(testLaunchOverrides())
        val result = verifier.verify(trajectory)
        result.shouldBeInstanceOf<OfflineReplayVerificationResultV1.Unavailable>()
    }

    test("unsupported worker protocol version is rejected by the contract") {
        val trajectory = fabricatedTrajectory()
        val request = VerifierWorkerRequestV1.of(trajectory)
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            VerifierWorkerProtocolV1Json.decodeRequest(
                VerifierWorkerProtocolV1Json.encodeRequest(request).replace(
                    "\"version\":1",
                    "\"version\":99",
                ),
            )
        }
    }

    test("horizon contract: horizon-reached episodes reconstruct with the exact durable range") {
        TransportedReplayReconstructorV1.computeReplayHorizon(
            claimantReplayActionCount = 40,
            horizonReached = true,
            override = null,
        ) shouldBe 40
        TransportedReplayReconstructorV1.computeReplayHorizon(
            claimantReplayActionCount = 1000,
            horizonReached = true,
            override = null,
        ) shouldBe 1000
    }

    test("horizon contract: natural terminations get headroom under the ceiling") {
        TransportedReplayReconstructorV1.computeReplayHorizon(
            claimantReplayActionCount = 40,
            horizonReached = false,
            override = null,
        ) shouldBe 40 + TransportedReplayReconstructorV1.REPLAY_HORIZON_HEADROOM
        TransportedReplayReconstructorV1.computeReplayHorizon(
            claimantReplayActionCount = TransportedReplayReconstructorV1.MAX_REPLAY_STEPS_CEILING - 1,
            horizonReached = false,
            override = null,
        ) shouldBe TransportedReplayReconstructorV1.MAX_REPLAY_STEPS_CEILING
    }

    test("horizon contract: overrides may only lower the horizon within the ceiling") {
        TransportedReplayReconstructorV1.computeReplayHorizon(40, true, 12) shouldBe 12
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            TransportedReplayReconstructorV1.computeReplayHorizon(
                40,
                true,
                TransportedReplayReconstructorV1.MAX_REPLAY_STEPS_CEILING + 1,
            )
        }
    }

    test("tampered claimant engine commit is rejected by the sealed authentication flow") {
        // Sealed worker flow (P1): the worker's performVerification only exposes the
        // authenticate-then-reconstruct seam. With a syntactically valid commit that cannot
        // equal this checkout's HEAD, the sealed seam throws before any reconstruction. The
        // non-git probe repository yields the bootstrap-doctrine failure, matching the real
        // worker contract (a HEAD mismatch against a real checkout maps to
        // SOURCE_REVISION_MISMATCH via the failure taxonomy).
        val repositoryRoot = Files.createTempDirectory("ka06-02-repo-probe-")
        try {
            io.kotest.assertions.throwables.shouldThrow<TransportedReplayReconstructionException> {
                TransportedReplayReconstructorV1.authenticate(
                    repositoryRoot = repositoryRoot,
                    expectedEngineCommit = "f".repeat(40),
                )
            }
        } finally {
            repositoryRoot.toFile().deleteRecursively()
        }
    }

    test("a syntactically valid but non-EXACT binding can never surface as VERIFIED (P2)") {
        // P2 negative control: the outer four identity fields all match, but the returned
        // binding carries UNVERIFIED fidelity — the request/result seam must reject it before
        // any Verified result can exist.
        val verifier = CrossBindingProbeVerifier(testLaunchOverrides())
        val trajectory = fabricatedTrajectory()
        val forged = VerifierWorkerResultV1(
            status = VerifierWorkerStatusV1.VERIFIED,
            verifiedResult = VerifiedWorkerVerificationV1(
                verifiedTrajectoryId = trajectory.trajectoryId,
                verifiedSemanticEpisodeId = trajectory.semanticEpisodeId,
                verifiedReplayContentIdentity =
                    trajectory.episodeMetadata.compactReplayLink.replayContentIdentity,
                verifiedReplayActionCount = trajectory.episodeMetadata.compactReplayLink.replayActionCount,
                replayTrajectoryBinding = fabricatedBinding(), // UNVERIFIED fidelity by default
            ),
        )
        verifier.crossBindingCheck(trajectory, forged)
            .shouldBeInstanceOf<OfflineReplayVerificationResultV1.Unavailable>()
    }

    test("an EXACT binding with internally mismatched identities is rejected (P2)") {
        // P2 negative control: the binding claims EXACT but its internal replay content
        // identity disagrees with the outer verified identity.
        val verifier = CrossBindingProbeVerifier(testLaunchOverrides())
        val trajectory = fabricatedTrajectory()
        val outerIdentity = trajectory.episodeMetadata.compactReplayLink.replayContentIdentity
        val genuine = fabricatedBinding(fidelity = ReplayFidelity.EXACT, replayActionCount = 0)
        val forged = VerifierWorkerResultV1(
            status = VerifierWorkerStatusV1.VERIFIED,
            verifiedResult = VerifiedWorkerVerificationV1(
                verifiedTrajectoryId = trajectory.trajectoryId,
                verifiedSemanticEpisodeId = trajectory.semanticEpisodeId,
                verifiedReplayContentIdentity = outerIdentity,
                verifiedReplayActionCount = trajectory.episodeMetadata.compactReplayLink.replayActionCount,
                replayTrajectoryBinding = genuine,
            ),
        )
        // Mutate the decoded wire form AFTER protocol decoding: the forged result now claims the
        // outer identity while the binding itself still names a different replay content
        // identity. The constructor invariant protects in-process construction; the seam must
        // catch exactly this wire-form divergence.
        val decoded = VerifierWorkerProtocolV1Json.decodeResult(
            VerifierWorkerProtocolV1Json.encodeResult(forged)
                .replace("\"value\":\"" + outerIdentity + "\"", "\"value\":\"" + "d".repeat(64) + "\""),
        )
        verifier.crossBindingCheck(trajectory, decoded)
            .shouldBeInstanceOf<OfflineReplayVerificationResultV1.Unavailable>()
    }

    test("environment identity repository re-derivation rejects wrong card/deck digests (P1)") {
        // P1 negative control with REAL curriculum deck authority: the re-derived card and deck
        // digests come from the accepted locked-pair curriculum sources, so a claimant carrying
        // fabricated digests fails before any reconstruction.
        val root = repositoryRoot()
        check(root.toFile().isDirectory && root.resolve(".git").toFile().exists()) {
            "repositoryRoot() must resolve the real git work tree, never a temp or module dir"
        }
        try {
            val akiri = CurriculumDeckSourceLoader(root)
                .load(CurriculumAiTournamentPreset.AKIRI_CHEVILL.sourcePaths[0])
            val chevill = CurriculumDeckSourceLoader(root)
                .load(CurriculumAiTournamentPreset.AKIRI_CHEVILL.sourcePaths[1])
            val reconstructor = TransportedReplayReconstructorV1(repositoryRoot = root)
            val cardDigest = reconstructor.lockedCardDefinitionDigest(akiri, chevill)
            check(cardDigest.length == 64) { "real card digest should be sha256 hex" }

            val wrongClaimant = fabricatedTrajectory(
                akiriDigest = "e".repeat(64),
                chevillDigest = "d".repeat(64),
            )
            io.kotest.assertions.throwables.shouldThrow<TransportedReplayReconstructionException> {
                reconstructor.deriveEnvironmentIdentity(wrongClaimant.episodeMetadata.environmentIdentity)
            }

            // The positive pole: all three repository-derived fields carry the real values
            // and re-derive without failure.
            reconstructor.deriveEnvironmentIdentity(
                fabricatedTrajectory(
                    akiriDigest = akiri.sourceDigest,
                    chevillDigest = chevill.sourceDigest,
                    cardDigest = cardDigest,
                ).episodeMetadata.environmentIdentity,
            )
        } finally {
            // `root` is the real git work tree (asserted above); it must survive the test.
            check(root.toFile().isDirectory) { "The real repository root must never be deleted" }
        }
    }
})

// ---------------------------------------------------------------------------
// Focused verifier probes (same module internals, no real worker launches)
// ---------------------------------------------------------------------------

/** The module's own runtime classpath + java executable, for test-only launch overrides. */
private fun testLaunchOverrides(): WorkerLaunchOverridesV1 {
    val javaBin = Path.of(System.getProperty("java.home")).resolve("bin").let { bin ->
        if (System.getProperty("os.name").lowercase().contains("windows")) bin.resolve("java.exe") else bin.resolve("java")
    }
    return WorkerLaunchOverridesV1(
        workerClasspath = System.getProperty("java.class.path")
            ?: error("test requires the JVM classpath"),
        javaExecutable = javaBin,
        workerMaxHeap = "-Xmx6g",
    )
}

/**
 * Exposes the request/result binding seam for direct assertion. This is the boundary that must
 * be self-validating (P2): a syntactically valid worker result must also be semantically
 * consistent before it surfaces as VERIFIED. Wraps the final verifier class (never a subclass).
 */
private class CrossBindingProbeVerifier(launch: WorkerLaunchOverridesV1) {
    private val verifier = ProductionOfflineReplayVerifierV1(
        repositoryRoot = Path.of("."),
        timeout = VerifierTimeoutV1(),
        launchOverrides = launch,
    )

    fun crossBindingCheck(
        trajectory: TrajectoryV1,
        result: VerifierWorkerResultV1,
    ): OfflineReplayVerificationResultV1 = verifier.invokeBoundResultForTest(trajectory, result)
}

/** Forces the bounded-timeout path deterministically. */
private class TimedOutWorkerVerifier(launch: WorkerLaunchOverridesV1) {
    private val verifier = ProductionOfflineReplayVerifierV1(
        repositoryRoot = Path.of("."),
        timeout = VerifierTimeoutV1(duration = 1, unit = TimeUnit.MICROSECONDS),
        launchOverrides = launch,
    )

    fun verify(trajectory: TrajectoryV1): OfflineReplayVerificationResultV1 = verifier.verify(trajectory)
}

/** Launches the fixed worker with an unwritable response path to force a real nonzero exit. */
private class CrashingWorkerVerifier(private val launch: WorkerLaunchOverridesV1) {
    fun launchAndAwait(trajectory: TrajectoryV1): Int {
        val request = VerifierWorkerRequestV1.of(trajectory)
        val workDirectory = Files.createTempDirectory("ka06-02-crash-probe-")
        val argFile = workDirectory.resolve("worker.args")
        val quoted: (String) -> String = { value ->
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        Files.writeString(
            argFile,
            listOf(
                launch.workerMaxHeap,
                "-cp",
                quoted(launch.workerClasspath),
                ProductionOfflineReplayVerifierV1.WORKER_MAIN_CLASS,
                "--request",
                quoted(
                    workDirectory.resolve("request.json").toAbsolutePath().toString(),
                ),
                "--repository-root",
                quoted(Path.of(".").toAbsolutePath().toString()),
                "--response",
                quoted(
                    workDirectory.resolve("does-not-exist").resolve("response.json")
                        .toAbsolutePath().toString(),
                ),
            ).joinToString("\n"),
            StandardCharsets.UTF_8,
        )
        Files.writeString(
            workDirectory.resolve("request.json"),
            VerifierWorkerProtocolV1Json.encodeRequest(request),
            StandardCharsets.UTF_8,
        )
        val process = ProcessBuilder(
            listOf(launch.javaExecutable.toString(), "@" + argFile.toAbsolutePath().toString()),
        ).redirectErrorStream(true).start()
        process.waitFor(2, TimeUnit.MINUTES)
        workDirectory.toFile().deleteRecursively()
        return process.exitValue()
    }
}

// ---------------------------------------------------------------------------
// Fabricated durable contracts (sha256-hex identities only; no engine run)
// ---------------------------------------------------------------------------

private fun sha256Hex(seed: String): String = A3SemanticJson.sha256(seed.toByteArray(StandardCharsets.UTF_8))

/**
 * The engine repository root, resolved through git from the running module directory (a Gradle
 * test's working directory is the module dir, so `user.dir` alone is not the work-tree root).
 * Tests must never mutate or delete this tree — the bootstrap doctrine requires it clean.
 */
private fun repositoryRoot(): Path {
    val root = Path.of(
        ProcessBuilder("git", "rev-parse", "--show-toplevel")
            .directory(Path.of(System.getProperty("user.dir")).toFile())
            .start()
            .inputStream
            .bufferedReader()
            .readLine()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: error("git rev-parse --show-toplevel found no repository root"),
    )
    check(root.toFile().isDirectory && root.resolve(".git").toFile().exists()) {
        "Repository root is not a git work tree: $root"
    }
    return root
}

private fun fabricatedEnvironmentIdentity(
    engineCommit: String,
    akiriDigest: String = sha256Hex("akiri-deck"),
    chevillDigest: String = sha256Hex("chevill-deck"),
    cardDigest: String = sha256Hex("card-definition"),
): EnvironmentIdentityV1 {
    val seat0 = EntityId("verifier-seat-0")
    val seat1 = EntityId("verifier-seat-1")
    return EnvironmentIdentityV1(
        engineCommit = engineCommit,
        cardDefinitionIdentity = cardDigest,
        akiriDeckIdentity = akiriDigest,
        chevillDeckIdentity = chevillDigest,
        format = "COMMANDER",
        attackMode = "MULTIPLE",
        startingHandSize = 7,
        skipMulligans = true,
        useHandSmoother = false,
        roster = listOf(
            RosterSeatV1(
                seatIndex = 0,
                playerId = seat0,
                role = "AKIRI",
                deckIdentity = akiriDigest,
                commanderDefinitionIdentity = "Akiri, Fearless Voyager",
            ),
            RosterSeatV1(
                seatIndex = 1,
                playerId = seat1,
                role = "CHEVILL",
                deckIdentity = chevillDigest,
                commanderDefinitionIdentity = "Chevill, Bane of Monsters",
            ),
        ),
        startingPlayer = seat0,
        actualEngineSeed = 7L,
    )
}

private fun fabricatedTrajectory(
    engineCommit: String = sha256Hex("fabricated-commit"),
    akiriDigest: String = sha256Hex("akiri-deck"),
    chevillDigest: String = sha256Hex("chevill-deck"),
    cardDigest: String = sha256Hex("card-definition"),
): TrajectoryV1 {
    val identity = fabricatedEnvironmentIdentity(engineCommit, akiriDigest, chevillDigest, cardDigest)
    val metadata = EpisodeMetadataV1(
        semanticEpisodeId = sha256Hex("semantic-episode"),
        collectionJobId = sha256Hex("collection-job"),
        environmentIdentity = identity,
        policyProvenance = PolicyProvenanceV1(
            behaviorPolicyIdentity = "fabricated-policy@v1",
            opponentPolicyIdentity = "fabricated-policy@v1",
            behaviorPolicyRole = "EXTERNAL_CONTROLLER",
            opponentPolicyRole = "EXTERNAL_CONTROLLER",
            policyRngIdentity = "explicit-seed/kotlin-policy-state-v1",
            policySeed = 42L,
            policySourceIdentity = "fabricated-policy-source@v1",
        ),
        compactReplayLink = CompactReplayLinkV1(
            replayVersion = CompactReplay.CURRENT_VERSION,
            replayContentIdentity = sha256Hex("replay-content"),
            replayActionCount = 0,
        ),
        closure = EpisodeClosureV1.GameTerminal(stepCount = 0, winnerId = null),
    )
    return TrajectoryV1(
        trajectoryId = sha256Hex("trajectory"),
        episodeMetadata = metadata,
        decisions = emptyList(),
    )
}

private fun fabricatedPlan(trajectory: TrajectoryV1): WorkloadPlanV1 {
    val job = WorkloadJobV1(
        jobOrdinal = 0,
        environmentIdentity = trajectory.episodeMetadata.environmentIdentity,
        policyProvenance = trajectory.episodeMetadata.policyProvenance,
    )
    return WorkloadPlanV1(
        workloadNamespace = "ka06-02-production-verifier-contract",
        rolloutGeneration = "2026-09-17-v1",
        behaviorPolicyCampaignIdentity = "fabricated-policy@v1",
        opponentPolicyCampaignIdentity = "fabricated-policy@v1",
        jobs = listOf(job),
    )
}

@Suppress("unused")
private fun planReference(): WorkloadPlanV1 = fabricatedPlan(fabricatedTrajectory())

private fun fabricatedBinding(): ReplayTrajectoryBindingV1 =
    fabricatedBinding(fidelity = ReplayFidelity.UNVERIFIED, replayActionCount = 0)

/**
 * Binding fixture over the 0-action range. EXACT requires the full frame evidence chain; the
 * single frame carries a minimal, digest-consistent observation/domain pair. This fixture is
 * the carrier for the P2 negative control: an outer-VERIFIED result whose binding is not EXACT
 * must be rejected by the request/result binding seam.
 */
private fun fabricatedBinding(fidelity: ReplayFidelity, replayActionCount: Int): ReplayTrajectoryBindingV1 {
    val contentIdentity = ReplayContentIdentityV1(
        replayVersion = CompactReplay.CURRENT_VERSION,
        value = sha256Hex("replay-content"),
    )
    val seat0 = EntityId("verifier-seat-0")
    val seat1 = EntityId("verifier-seat-1")
    val domain = CompleteLegalDomainV1(kind = CompleteLegalDomainKind.ACTION_CANDIDATES)
    val frame = VerifiedReplayFrame(
        replayActionIndex = 0,
        perspectivePlayerId = seat0,
        observation = PlayerObservationV1(
            wireSchemaHash = com.wingedsheep.gym.contract.SchemaHash.CURRENT,
            perspectivePlayerId = seat0,
            agentToAct = null,
            turnNumber = 1,
            phase = com.wingedsheep.sdk.core.Phase.BEGINNING,
            step = com.wingedsheep.sdk.core.Step.UNTAP,
            activePlayerId = seat0,
            priorityPlayerId = seat0,
            players = listOf(
                PlayerView(
                    id = seat0,
                    name = "Akiri",
                    lifeTotal = 40,
                    handSize = 0,
                    librarySize = 0,
                    graveyardSize = 0,
                    exileSize = 0,
                    manaPool = ManaPoolView(),
                    isPerspective = true,
                    isActive = true,
                    hasPriority = true,
                    hasLost = false,
                ),
                PlayerView(
                    id = seat1,
                    name = "Chevill",
                    lifeTotal = 40,
                    handSize = 0,
                    librarySize = 0,
                    graveyardSize = 0,
                    exileSize = 0,
                    manaPool = ManaPoolView(),
                    isPerspective = false,
                    isActive = false,
                    hasPriority = false,
                    hasLost = false,
                ),
            ),
            zones = emptyList(),
            stack = emptyList(),
            pendingDecision = null,
            terminated = false,
            truncated = false,
            winnerId = null,
            observationDigest = "0".repeat(64),
        ),
        domain = domain,
        candidateDomainDigest = CandidateDomainDigestV1.from(domain),
    )
    return ReplayTrajectoryBindingV1(
        verificationBinding = ReplayVerificationBindingV1(
            replayContentIdentity = contentIdentity,
            verification = VerifiedReplayVerification(
                replayVersion = CompactReplay.CURRENT_VERSION,
                replayActionCount = replayActionCount,
                verifiedActionCount = replayActionCount,
                fidelity = fidelity,
                frames = if (fidelity == ReplayFidelity.EXACT) listOf(frame) else emptyList(),
                initialCheckpointVerified = fidelity == ReplayFidelity.EXACT,
                intermediateCheckpointsVerified = fidelity == ReplayFidelity.EXACT,
                tailCheckpointVerified = fidelity == ReplayFidelity.EXACT,
                closure = EpisodeClosureV1.GameTerminal(stepCount = 0, winnerId = null),
            ),
        ),
        chosenInputBinding = ReplayChosenInputBindingV1(
            replayContentIdentity = contentIdentity,
            replayActionCount = replayActionCount,
            chosenInputs = emptyList(),
        ),
    )
}
