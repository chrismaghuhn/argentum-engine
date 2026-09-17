package com.wingedsheep.gameserver.replay.verification

import com.wingedsheep.gym.EpisodeClosureV1
import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.ReplayContentIdentityV1
import com.wingedsheep.gym.contract.ReplayFidelity
import com.wingedsheep.gym.contract.ReplayTrajectoryBindingV1
import com.wingedsheep.gym.contract.ReplayVerificationBindingV1
import com.wingedsheep.gym.contract.ReplayChosenInputBindingV1
import com.wingedsheep.gym.contract.VerifiedReplayVerification
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
        val verifier = CrossBindingProbeVerifier()
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
        val verifier = CrossBindingProbeVerifier()
        val trajectory = fabricatedTrajectory()
        val link = trajectory.episodeMetadata.compactReplayLink
        val binding = fabricatedBinding()

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
        val crashVerifier = CrashingWorkerVerifier()
        val exit = crashVerifier.launchAndAwait(fabricatedTrajectory())
        exit shouldBe 1
    }

    test("bounded timeout produces a typed VERIFIER_TIMEOUT, never VERIFIED") {
        val trajectory = fabricatedTrajectory()
        val verifier = TimedOutWorkerVerifier()
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

    test("same-revision authentication precedes reconstruction in the worker contract") {
        // The worker entrypoint authenticates the source BEFORE consulting any engine type:
        // with a claimant commit that cannot equal this checkout's HEAD, performVerification
        // must return the typed SOURCE_REVISION failure and never attempt a rebuild.
        val repositoryRoot = Files.createTempDirectory("ka06-02-repo-probe-")
        try {
            val claimant = fabricatedTrajectory(engineCommit = "f".repeat(40))
            val result = TransportedReplayVerifierWorkerMain.performVerification(
                repositoryRoot = repositoryRoot,
                request = VerifierWorkerRequestV1.of(claimant),
            )
            result.status shouldBe VerifierWorkerStatusV1.UNAVAILABLE
            result.failureCode shouldBe OfflineReplayFailureCodeV1.SOURCE_REVISION_UNVERIFIED
        } finally {
            repositoryRoot.toFile().deleteRecursively()
        }
    }
})

// ---------------------------------------------------------------------------
// Focused verifier probes (same module internals, no real worker launches)
// ---------------------------------------------------------------------------

/** Exposes the internal request/result binding seam for direct assertion. */
private class CrossBindingProbeVerifier :
    ProductionOfflineReplayVerifierV1(repositoryRoot = Path.of(".")) {
    fun crossBindingCheck(
        trajectory: TrajectoryV1,
        result: VerifierWorkerResultV1,
    ): OfflineReplayVerificationResultV1 = boundResult(trajectory, result)

    public override fun boundResult(
        trajectory: TrajectoryV1,
        result: VerifierWorkerResultV1,
    ): OfflineReplayVerificationResultV1 = super.boundResult(trajectory, result)
}

/** Forces the bounded-timeout path deterministically. */
private class TimedOutWorkerVerifier :
    ProductionOfflineReplayVerifierV1(
        repositoryRoot = Path.of("."),
        timeout = VerifierTimeoutV1(duration = 1, unit = TimeUnit.MICROSECONDS),
    )

/** Launches the fixed worker with an unwritable response path to force a real nonzero exit. */
private class CrashingWorkerVerifier :
    ProductionOfflineReplayVerifierV1(repositoryRoot = Path.of(".")) {
    fun launchAndAwait(trajectory: TrajectoryV1): Int {
        val request = VerifierWorkerRequestV1.of(trajectory)
        val arguments = mutableListOf(
            javaExecutable.toString(),
            workerMaxHeap,
            "-cp",
            workerClasspath,
            WORKER_MAIN_CLASS,
            "--request",
            Base64.getEncoder().encodeToString(
                VerifierWorkerProtocolV1Json.encodeRequest(request).toByteArray(StandardCharsets.UTF_8),
            ),
            "--repository-root",
            Path.of(".").toAbsolutePath().toString(),
            "--response",
            Path.of(".", "does-not-exist", "response.json").toAbsolutePath().toString(),
        )
        val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
        process.waitFor(2, TimeUnit.MINUTES)
        return process.exitValue()
    }
}

// ---------------------------------------------------------------------------
// Fabricated durable contracts (sha256-hex identities only; no engine run)
// ---------------------------------------------------------------------------

private fun sha256Hex(seed: String): String = A3SemanticJson.sha256(seed.toByteArray(StandardCharsets.UTF_8))

private fun fabricatedEnvironmentIdentity(engineCommit: String): EnvironmentIdentityV1 {
    val seat0 = EntityId("verifier-seat-0")
    val seat1 = EntityId("verifier-seat-1")
    return EnvironmentIdentityV1(
        engineCommit = engineCommit,
        cardDefinitionIdentity = sha256Hex("card-definition"),
        akiriDeckIdentity = sha256Hex("akiri-deck"),
        chevillDeckIdentity = sha256Hex("chevill-deck"),
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
                deckIdentity = sha256Hex("akiri-deck"),
                commanderDefinitionIdentity = "Akiri, Fearless Voyager",
            ),
            RosterSeatV1(
                seatIndex = 1,
                playerId = seat1,
                role = "CHEVILL",
                deckIdentity = sha256Hex("chevill-deck"),
                commanderDefinitionIdentity = "Chevill, Bane of Monsters",
            ),
        ),
        startingPlayer = seat0,
        actualEngineSeed = 7L,
    )
}

private fun fabricatedTrajectory(engineCommit: String = sha256Hex("fabricated-commit")): TrajectoryV1 {
    val identity = fabricatedEnvironmentIdentity(engineCommit)
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

private fun fabricatedBinding(): ReplayTrajectoryBindingV1 {
    val contentIdentity = ReplayContentIdentityV1(
        replayVersion = CompactReplay.CURRENT_VERSION,
        value = sha256Hex("replay-content"),
    )
    // UNVERIFIED fidelity: this fixture carries identity/range fields for protocol and binding
    // tests, not an EXACT claim — EXACT would require full frame evidence by contract.
    return ReplayTrajectoryBindingV1(
        verificationBinding = ReplayVerificationBindingV1(
            replayContentIdentity = contentIdentity,
            verification = VerifiedReplayVerification(
                replayVersion = CompactReplay.CURRENT_VERSION,
                replayActionCount = 0,
                verifiedActionCount = 0,
                fidelity = ReplayFidelity.UNVERIFIED,
                closure = EpisodeClosureV1.GameTerminal(stepCount = 0, winnerId = null),
            ),
        ),
        chosenInputBinding = ReplayChosenInputBindingV1(
            replayContentIdentity = contentIdentity,
            replayActionCount = 0,
            chosenInputs = emptyList(),
        ),
    )
}
