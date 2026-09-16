package com.wingedsheep.gym.trainer.actor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.time.Duration.Companion.hours

/**
 * Opt-in strict re-import gate for downloaded Kaggle actor output (KA05 #18). The test is disabled
 * unless KA05_DOWNLOAD_ROOT points at the untrusted exported copy, so ordinary CI never depends on
 * a local multi-gigabyte artifact. The envelope is reopened only through the existing strict
 * reader/admission APIs and must fail closed on the missing transported replay proof.
 */
class Ka05DownloadedOutputReimportTest : FunSpec({
    test("downloaded Kaggle output strictly reimports and keeps missing replay proof fail closed")
        .config(enabled = System.getenv("KA05_DOWNLOAD_ROOT") != null, timeout = 2.hours) {
            val downloadRoot = Path.of(requireNotNull(System.getenv("KA05_DOWNLOAD_ROOT")))
            val envelopes = Files.walk(downloadRoot).use { stream ->
                stream.filter { path ->
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
                        path.fileName.toString().startsWith("envelope-")
                }.toList()
            }

            envelopes shouldHaveSize 1
            val imported = envelopes.map(LocalPublicationEnvelopeV1::reimport)
            imported.single().streamEpisodes().count() shouldBe 16L

            val admission = OfflineAdmissionV1.admit(
                OfflineAdmissionRequestV1(
                    assignment = imported.single().assignment,
                    sources = imported,
                    replayVerifier = null,
                ),
            )

            admission.offlineReplayReverification shouldBe
                OfflineReplayReverificationStatusV1.NO_INDEPENDENT_PROOF
            admission.datasetEligible shouldBe false
            admission.acceptedSources shouldBe emptyList()
            admission.ledger.entries.all { it.membershipState == MembershipStateV1.MISSING } shouldBe true
        }
})