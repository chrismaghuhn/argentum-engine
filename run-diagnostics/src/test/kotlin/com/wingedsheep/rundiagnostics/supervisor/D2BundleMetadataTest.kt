package com.wingedsheep.rundiagnostics.supervisor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

class D2BundleMetadataTest : FunSpec({
    test("summary separates physical file availability from probe availability") {
        val root = Files.createTempDirectory("run-diagnostics-d2-probe-metadata-")
        try {
            val result = DiagnosticBundleWriter(root).write(
                bundleInput(
                    status = null,
                    metrics = ProcessMetricsV1(
                        availability = EvidenceAvailability.MISSING,
                        failureCode = SupervisorFailureCode.METRICS_UNAVAILABLE,
                    ),
                    jvmResults = listOf(
                        JvmCommandResult(
                            kind = JvmCommandKind.THREAD_PRINT,
                            availability = EvidenceAvailability.TIMED_OUT,
                            failureCode = SupervisorFailureCode.COMMAND_TIMED_OUT,
                        ),
                    ),
                    safeArtifactSizes = emptyList(),
                ),
            )
            val records = result.summary!!.files.associateBy { it.name }

            records.getValue("status.json").availability shouldBe EvidenceAvailability.AVAILABLE
            records.getValue("status.json").probeAvailability shouldBe EvidenceAvailability.MISSING
            records.getValue("status.json").probeFailureCode shouldBe SupervisorFailureCode.STATUS_MISSING
            records.getValue("process-metrics.json").probeAvailability shouldBe EvidenceAvailability.MISSING
            records.getValue("artifact-sizes.json").probeAvailability shouldBe EvidenceAvailability.NOT_CONFIGURED
            records.getValue("recent-stages.json").probeAvailability shouldBe EvidenceAvailability.NOT_CONFIGURED
            records.values.single { it.name == "privileged/thread-dump-0.txt" }
                .probeAvailability shouldBe EvidenceAvailability.TIMED_OUT
            Files.readString(result.bundleDirectory!!.resolve("summary.json")) shouldContain
                "\"probeAvailability\":\"MISSING\""
            Files.readString(result.bundleDirectory.resolve("summary.json")) shouldContain
                "\"probeAvailability\":\"TIMED_OUT\""
            Files.readString(result.bundleDirectory.resolve("summary.json")) shouldContain
                "\"probeAvailability\":\"NOT_CONFIGURED\""
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("bundle manifest carries operational metadata, configuration, and probe records") {
        val root = Files.createTempDirectory("run-diagnostics-d2-manifest-metadata-")
        try {
            val result = DiagnosticBundleWriter(root).write(
                bundleInput(
                    status = null,
                    jvmResults = emptyList(),
                    safeArtifactSizes = emptyList(),
                ),
            )
            val manifest = Files.readString(result.bundleDirectory!!.resolve("bundle.json"))

            manifest shouldContain "\"trigger\":\"USEFUL_PROGRESS_STALE\""
            manifest shouldContain "\"classification\":\"SUSPECTED_STALL\""
            manifest shouldContain "\"action\":\"CAPTURE_DIAGNOSTICS_AND_CONTINUE\""
            manifest shouldContain "\"configuration\":"
            manifest shouldContain "\"heartbeatTimeoutMillis\":"
            manifest shouldContain "\"files\":"
            manifest shouldContain "\"status.json\""
            manifest shouldContain "\"probeAvailability\":\"MISSING\""
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("does not publish a manifest when the later summary cannot fit the bundle budget") {
        val baselineRoot = Files.createTempDirectory("run-diagnostics-d2-ordering-baseline-")
        val constrainedRoot = Files.createTempDirectory("run-diagnostics-d2-ordering-constrained-")
        try {
            val input = bundleInput(
                status = null,
                jvmResults = emptyList(),
                safeArtifactSizes = emptyList(),
            )
            val baseline = DiagnosticBundleWriter(baselineRoot).write(input)
            val baselineDirectory = baseline.bundleDirectory!!
            val evidenceBytes = Files.walk(baselineDirectory).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString() !in setOf("bundle.json", "summary.json") }
                    .mapToLong { path -> Files.size(path) }
                    .sum()
            }
            val manifestBytes = Files.size(baselineDirectory.resolve("bundle.json"))
            val summaryBytes = Files.size(baselineDirectory.resolve("summary.json"))
            val constrainedBudget = (evidenceBytes + maxOf(manifestBytes, summaryBytes) + 128L).toInt()

            val result = DiagnosticBundleWriter(
                constrainedRoot,
                maxBundleBytes = constrainedBudget,
            ).write(input)
            val constrainedDirectory = result.bundleDirectory!!

            result.availability shouldBe EvidenceAvailability.FAILED
            Files.exists(constrainedDirectory.resolve("summary.json")) shouldBe true
            Files.exists(constrainedDirectory.resolve("bundle.json")) shouldBe false
        } finally {
            baselineRoot.toFile().deleteRecursively()
            constrainedRoot.toFile().deleteRecursively()
        }
    }
})
