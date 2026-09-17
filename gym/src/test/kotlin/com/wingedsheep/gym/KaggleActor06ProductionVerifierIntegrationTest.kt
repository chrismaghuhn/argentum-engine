package com.wingedsheep.gym

import com.wingedsheep.gym.contract.ReplayFidelity
import com.wingedsheep.gym.trainer.actor.LocalPublicationEnvelopeV1
import com.wingedsheep.gym.trainer.actor.MembershipStateV1
import com.wingedsheep.gym.trainer.actor.OfflineAdmissionRequestV1
import com.wingedsheep.gym.trainer.actor.OfflineAdmissionV1
import com.wingedsheep.gym.trainer.actor.OfflineReplayFailureCodeV1
import com.wingedsheep.gym.trainer.actor.OfflineReplayReverificationStatusV1
import com.wingedsheep.gym.trainer.actor.OfflineReplayVerificationResultV1
import com.wingedsheep.gym.trainer.actor.OfflineReplayVerifierV1
import com.wingedsheep.gym.trainer.actor.WorkAssignmentV1
import com.wingedsheep.gym.trainer.trajectory.DatasetMetadataV1
import com.wingedsheep.gameserver.replay.verification.ProductionOfflineReplayVerifierV1
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger

/**
 * KA06 _02 — end-to-end production verifier integration inside the accepted KA06 gate.
 *
 * The [KaggleActor06TransportedReplayHarness.Transport] producer publishes a real durable
 * envelope; the production [ProductionOfflineReplayVerifierV1] (a separate worker JVM with
 * same-revision authentication and request binding) must independently reconstruct it, and the
 * real [OfflineAdmissionV1] must grant dataset eligibility only through that verified path.
 * The characterization spec remains the process-boundary oracle; this spec proves the
 * productionized primitive reproduces the characterized reconstruction contract.
 */
class KaggleActor06ProductionVerifierIntegrationTest : FunSpec({

    test("production verifier independently reconstructs a transported episode and admits it") {
        val transport = KaggleActor06TransportedReplayHarness.Transport()
        val verifier = ProductionOfflineReplayVerifierV1(
            repositoryRoot = KaggleActor06TransportedReplayHarness.ka06RepositoryRoot(),
        )

        val verified = verifier.verify(transport.producerTrajectory)
        check(verified is OfflineReplayVerificationResultV1.Verified) {
            "Production verifier did not verify the transported episode: $verified"
        }
        val binding = verified.replayTrajectoryBinding
        binding.verificationBinding.verification.fidelity shouldBe ReplayFidelity.EXACT
        binding.verificationBinding.verification.completeRangeVerified shouldBe true

        // Determinism (§20/§32): a second independent verification produces identical evidence.
        val second = verifier.verify(transport.producerTrajectory)
        check(second is OfflineReplayVerificationResultV1.Verified)
        second.replayTrajectoryBinding shouldBe binding

        // Real offline admission with the production verifier dependency.
        val imported = LocalPublicationEnvelopeV1.reimport(transport.envelopeDirectory)
        val admission = OfflineAdmissionV1.admit(
            OfflineAdmissionRequestV1(
                assignment = WorkAssignmentV1.from(transport.plan, listOf(0)),
                sources = listOf(imported),
                replayVerifier = verifier,
            ),
        )
        admission.offlineReplayReverification shouldBe OfflineReplayReverificationStatusV1.VERIFIED
        admission.datasetEligible shouldBe true
        admission.acceptedSources.size shouldBe 1
        admission.acceptedSources.single().replayTrajectoryBinding shouldBe binding

        // The no-verifier default stays fail-closed (§30) on the same real envelope.
        val noProof = OfflineAdmissionV1.admit(
            OfflineAdmissionRequestV1(
                assignment = WorkAssignmentV1.from(transport.plan, listOf(0)),
                sources = listOf(imported),
                replayVerifier = null,
            ),
        )
        noProof.offlineReplayReverification shouldBe
            OfflineReplayReverificationStatusV1.NO_INDEPENDENT_PROOF
        noProof.datasetEligible shouldBe false
        noProof.acceptedSources shouldBe emptyList()
    }

    test("duplicate durable claims of one deterministic episode accept once") {
        val first = KaggleActor06TransportedReplayHarness.Transport()
        val second = KaggleActor06TransportedReplayHarness.Transport()
        val verifier = ProductionOfflineReplayVerifierV1(
            repositoryRoot = KaggleActor06TransportedReplayHarness.ka06RepositoryRoot(),
        )
        check(first.claimantTrajectoryId == second.claimantTrajectoryId) {
            "Deterministic producer episodes must carry identical trajectory identities"
        }

        val admission = OfflineAdmissionV1.admit(
            OfflineAdmissionRequestV1(
                assignment = WorkAssignmentV1.from(first.plan, listOf(0)),
                sources = listOf(
                    LocalPublicationEnvelopeV1.reimport(first.envelopeDirectory),
                    LocalPublicationEnvelopeV1.reimport(second.envelopeDirectory),
                ),
                replayVerifier = verifier,
            ),
        )
        admission.offlineReplayReverification shouldBe OfflineReplayReverificationStatusV1.VERIFIED
        admission.datasetEligible shouldBe true
        admission.acceptedSources.size shouldBe 1
        admission.ledger.entries.single().membershipState shouldBe MembershipStateV1.ACCEPTED
        admission.ledger.entries.single().claims.size shouldBe 2
    }

    test("one failing claim blocks dataset eligibility for the whole request") {
        val transport = KaggleActor06TransportedReplayHarness.Transport()
        val verifier = ProductionOfflineReplayVerifierV1(
            repositoryRoot = KaggleActor06TransportedReplayHarness.ka06RepositoryRoot(),
        )
        val imported = LocalPublicationEnvelopeV1.reimport(transport.envelopeDirectory)
        val calls = AtomicInteger(0)
        val partiallyFailingVerifier = OfflineReplayVerifierV1 { trajectory ->
            if (calls.incrementAndGet() == 1) {
                verifier.verify(trajectory)
            } else {
                OfflineReplayVerificationResultV1.Unavailable(
                    OfflineReplayFailureCodeV1.REPLAY_NOT_EXACT,
                )
            }
        }

        val admission = OfflineAdmissionV1.admit(
            OfflineAdmissionRequestV1(
                assignment = WorkAssignmentV1.from(transport.plan, listOf(0)),
                sources = listOf(imported, imported),
                replayVerifier = partiallyFailingVerifier,
            ),
        )
        // Existing authority semantics (§24): verifier failure flips the request-global status
        // to BLOCKED and kills dataset eligibility, while the ledger keeps per-item membership
        // of the trajectories that did verify. The fail-closed gate is datasetEligible / repack.
        admission.offlineReplayReverification shouldBe OfflineReplayReverificationStatusV1.BLOCKED
        admission.datasetEligible shouldBe false
        runCatching {
            OfflineAdmissionV1.repackAcceptedSources(
                admission = admission,
                outputDirectory = tempdir(),
                metadata = DatasetMetadataV1(),
            )
        }.isFailure shouldBe true
    }
})

private fun tempdir(): java.nio.file.Path =
    java.nio.file.Files.createTempDirectory("ka06-negative-repack")
