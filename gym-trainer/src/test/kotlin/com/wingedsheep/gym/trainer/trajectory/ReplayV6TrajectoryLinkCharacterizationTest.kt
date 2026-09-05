package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.gym.contract.ReplayContentIdentityV1
import com.wingedsheep.gym.contract.ReplayTrajectoryBindingV1
import com.wingedsheep.gym.contract.ReplayVerificationBindingV1
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Characterizes the current v6 replay to Trajectory V1 linkage seam.
 *
 * The test is green only because it asserts the current rejection. The desired future behavior is
 * for the same v6 evidence to admit; that remains intentionally RED until the versioned contract is
 * changed in a separately authorized task.
 */
class ReplayV6TrajectoryLinkCharacterizationTest : FunSpec({

    test("current v6 replay evidence cannot cross the v5 trajectory link") {
        val fixture = validFixture()
        val v6Identity = fixture.binding.verificationBinding.replayContentIdentity.copy(
            replayVersion = 6,
        )
        val v6Verification = fixture.binding.verificationBinding.verification.copy(
            replayVersion = 6,
        )
        val v6Binding = ReplayTrajectoryBindingV1(
            verificationBinding = ReplayVerificationBindingV1(
                replayContentIdentity = v6Identity,
                verification = v6Verification,
            ),
            chosenInputBinding = fixture.binding.chosenInputBinding.copy(
                replayContentIdentity = v6Identity,
            ),
        )

        v6Binding.verificationBinding.replayContentIdentity.replayVersion shouldBe 6
        v6Binding.verificationBinding.verification.replayVersion shouldBe 6
        v6Binding.verificationBinding.verification.completeRangeVerified shouldBe true

        shouldThrow<IllegalArgumentException> {
            CompactReplayLinkV1(
                replayVersion = 6,
                replayContentIdentity = v6Identity.value,
                replayActionCount = fixture.trajectory.decisions.size,
            )
        }.message shouldBe "Unsupported linked replay version: 6"

        val downlabelled = TrajectoryV1Admission.admit(
            trajectory = fixture.trajectory,
            binding = v6Binding,
            episodeOrdinal = 0,
        ).shouldBeInstanceOf<TrajectoryAdmissionResult.Quarantined>()

        downlabelled.metadata.reason shouldBe TrajectoryQuarantineReason.REPLAY_VERSION_MISMATCH
    }
})
