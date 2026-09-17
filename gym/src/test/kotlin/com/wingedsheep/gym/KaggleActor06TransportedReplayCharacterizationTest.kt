package com.wingedsheep.gym

import com.wingedsheep.gym.contract.ReplayFidelity
import com.wingedsheep.gym.trainer.actor.OfflineReplayReverificationStatusV1
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString

private const val KA06_VERIFIER_WORKER_TIMEOUT_MINUTES = 45L

/**
 * KA06 — independent transported-replay reconstruction authority characterization.
 *
 * One deterministic local episode is produced through the accepted producer path
 * (trusted Gym/Environment V1 → real A4 replay fold → real A5 contracts → real A6 admission →
 * real B2 publication → real local publication envelope), and an independent verifier JVM
 * process must reconstruct that episode using only the durable transported artifact plus the
 * repository runtime configuration that the durable environment identity permits.
 *
 * PROCESS A (this test JVM) is the producer. PROCESS B (a fresh JVM running
 * [KaggleActor06VerifierWorkerMain] over the test runtime classpath) is the verifier. The
 * verifier receives only the envelope directory path. The producer's trajectory, A4 binding,
 * replay objects, engine objects, and registries never cross the boundary in memory; the test
 * JVM itself never executes a verifier mode function except through the worker process.
 */
class KaggleActor06TransportedReplayCharacterizationTest : FunSpec({

    test("transported local episode reconstructs exactly inside an independent verifier process") {
        val transport = KaggleActor06TransportedReplayHarness.Transport()

        // ---- Positive characterization (fresh verifier JVM, durable inputs only) ----
        val verify = runVerifier(
            transport.envelopeDirectory,
            "verify",
        )
        check(verify.success) { "verify worker failed: ${verify.workerFailure}" }
        verify.status shouldBe "VERIFIED"

        val verifierSemanticEpisodeId = checkNotNull(verify.field("verifierSemanticEpisodeId"))
        val verifierTrajectoryId = checkNotNull(verify.field("verifierTrajectoryId"))
        val verifierContentIdentity = checkNotNull(verify.field("verifierContentIdentity"))
        val verifierActionCount = checkNotNull(verify.field("verifierReplayActionCount"))
        val verifierObservationDigest = checkNotNull(verify.field("verifierObservationDigest"))
        val verifierDomainDigest = checkNotNull(verify.field("verifierDomainDigest"))
        val verifierChoiceDigest = checkNotNull(verify.field("verifierChoiceDigest"))

        verifierSemanticEpisodeId shouldBe transport.claimantSemanticEpisodeId
        verifierTrajectoryId shouldBe transport.claimantTrajectoryId
        verifierContentIdentity shouldBe transport.claimantReplayContentIdentity
        verifierActionCount shouldBe transport.claimantReplayActionCount.toString()

        // ---- §28 determinism repetition: second independent verifier run agrees ----
        val verifyAgain = runVerifier(transport.envelopeDirectory, "verify")
        check(verifyAgain.success) { "second verify worker failed: ${verifyAgain.workerFailure}" }
        verifyAgain.field("verifierTrajectoryId") shouldBe verifierTrajectoryId
        verifyAgain.field("verifierContentIdentity") shouldBe verifierContentIdentity
        verifyAgain.field("verifierSemanticEpisodeId") shouldBe verifierSemanticEpisodeId
        verifyAgain.field("verifierReplayActionCount") shouldBe verifierActionCount
        verifyAgain.field("verifierObservationDigest") shouldBe verifierObservationDigest
        verifyAgain.field("verifierDomainDigest") shouldBe verifierDomainDigest
        verifyAgain.field("verifierChoiceDigest") shouldBe verifierChoiceDigest

        // ---- §20 negative control A: tampered engine seed ----
        val tamperedSeed = runVerifier(transport.envelopeDirectory, "tampered-seed")
        check(tamperedSeed.success) { "tampered-seed worker failed: ${tamperedSeed.workerFailure}" }
        tamperedSeed.field("result") shouldBe "rejected"

        // ---- §20 negative control B: tampered semantic choice ----
        val tamperedChoice = runVerifier(transport.envelopeDirectory, "tampered-choice")
        check(tamperedChoice.success) { "tampered-choice worker failed: ${tamperedChoice.workerFailure}" }
        tamperedChoice.field("result") shouldBe "rejected"

        // ---- §20 negative control C: truncated choice range ----
        val truncated = runVerifier(transport.envelopeDirectory, "truncated-range")
        check(truncated.success) { "truncated-range worker failed: ${truncated.workerFailure}" }
        truncated.field("result") shouldBe "rejected"

        // ---- §20 negative control D: wrong deck / environment identity ----
        val wrongEnvironment = runVerifier(transport.envelopeDirectory, "wrong-environment")
        check(wrongEnvironment.success) { "wrong-environment worker failed: ${wrongEnvironment.workerFailure}" }
        wrongEnvironment.field("result") shouldBe "rejected-before-exact"

        // ---- §20 negative control F: tampered engine commit (P1 remediation) ----
        // The verifier must authenticate its own executed source revision against the durable
        // engineCommit and stop BEFORE any reconstruction/EXACT claim when they disagree.
        val tamperedCommit = runVerifier(transport.envelopeDirectory, "tampered-engine-commit")
        check(tamperedCommit.success) {
            "tampered-engine-commit worker failed: ${tamperedCommit.workerFailure}"
        }
        tamperedCommit.field("result") shouldBe "rejected-before-exact"

        // ---- §20 negative control E: wrong replay content identity ----
        val wrongReplayIdentity = runVerifier(transport.envelopeDirectory, "wrong-replay-identity")
        check(wrongReplayIdentity.success) {
            "wrong-replay-identity worker failed: ${wrongReplayIdentity.workerFailure}"
        }
        wrongReplayIdentity.field("result") shouldBe "rejected"

        // ---- §23 OfflineAdmission test-only probe (positive + fail-closed) ----
        // The probe adapter binds its verification to the exact requested trajectory (P2
        // remediation) but remains a test-only structural probe: it is NOT the production
        // verifier, and _02 must not adopt the adapter without its own request→verification
        // binding per the accepted canonical identity contract.
        val admissionVerified = runVerifier(transport.envelopeDirectory, "admission-probe-verifier")
        check(admissionVerified.success) {
            "admission-probe-verifier worker failed: ${admissionVerified.workerFailure}"
        }
        admissionVerified.status shouldBe OfflineReplayReverificationStatusV1.VERIFIED.name
        admissionVerified.field("datasetEligible") shouldBe "true"
        admissionVerified.field("acceptedCount") shouldBe "1"

        val admissionNoProof = runVerifier(transport.envelopeDirectory, "admission-probe-no-proof")
        check(admissionNoProof.success) {
            "admission-probe-no-proof worker failed: ${admissionNoProof.workerFailure}"
        }
        admissionNoProof.status shouldBe OfflineReplayReverificationStatusV1.NO_INDEPENDENT_PROOF.name
        admissionNoProof.field("datasetEligible") shouldBe "false"
        admissionNoProof.field("acceptedCount") shouldBe "0"
    }

    test("worker failure surfaces as a failed verifier process") {
        // An envelope path that cannot exist must produce a failed worker exit (fail-closed
        // plumbing check), not a silent success.
        val missing = Files.createTempDirectory("ka06-missing-").resolve("no-envelope")
        val exit = runVerifier(missing, "verify", timeoutMinutes = 10)
        exit.success shouldBe false
        checkNotNull(exit.workerFailure)
    }
}) {
    companion object
}

/** Launch a fresh verifier JVM process and parse its exit file. */
private fun runVerifier(
    envelopeDirectory: Path,
    mode: String,
    timeoutMinutes: Long = KA06_VERIFIER_WORKER_TIMEOUT_MINUTES,
): KaggleActor06VerifierExit {
    val workDirectory = Files.createTempDirectory("ka06-verifier-")
    val exitFile = workDirectory.resolve("ka06-verifier-exit.json")
    val javaBin = Path.of(System.getProperty("java.home")).resolve("bin").let { bin ->
        val executable = if (System.getProperty("os.name").lowercase().contains("windows")) {
            bin.resolve("java.exe")
        } else {
            bin.resolve("java")
        }
        executable.absolutePathString()
    }
    val classpath = System.getProperty("java.class.path")
        ?: error("KA06 verifier worker requires the JVM test classpath")
    val repositoryRoot = KaggleActor06TransportedReplayHarness.ka06RepositoryRoot()
    val process = ProcessBuilder(
        listOf(
            javaBin,
            "-Xmx6g",
            "-cp",
            classpath,
            "com.wingedsheep.gym.KaggleActor06VerifierWorkerMain",
            mode,
            envelopeDirectory.absolutePathString(),
            exitFile.absolutePathString(),
        ),
    )
        .directory(repositoryRoot.toFile())
        .redirectErrorStream(true)
        .start()
    val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
    val output = String(process.inputStream.readAllBytes())
    if (!finished) {
        process.destroyForcibly()
        error("KA06 verifier worker timed out in mode $mode")
    }
    check(Files.exists(exitFile)) {
        "KA06 verifier worker produced no exit file in mode $mode:\n$output"
    }
    return KaggleActor06VerifierExit.parse(Files.readString(exitFile))
}
