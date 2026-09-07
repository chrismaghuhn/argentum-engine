package com.wingedsheep.rundiagnostics.supervisor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.time.Instant

class DiagnosticBundleWriterTest : FunSpec({
    test("writes safe evidence separately from privileged JVM text") {
        val root = Files.createTempDirectory("run-diagnostics-bundle-")
        try {
            val writer = DiagnosticBundleWriter(root)
            val result = writer.write(
                bundleInput(
                    jvmResults = listOf(
                        JvmCommandResult(
                            kind = JvmCommandKind.THREAD_PRINT,
                            availability = EvidenceAvailability.AVAILABLE,
                            output = "PRIVATE_THREAD_STACK_CONTENT",
                        ),
                    ),
                    safeArtifactSizes = listOf(SafeArtifactSizeV1("artifact-0", bytes = 123)),
                ),
            )

            result.availability shouldBe EvidenceAvailability.AVAILABLE
            val summaryModel = result.summary ?: error("bundle summary was not written")
            val directory = result.bundleDirectory!!
            val summary = Files.readString(directory.resolve("summary.json"))
            summary shouldNotContain "PRIVATE_THREAD_STACK_CONTENT"
            summary shouldContain "DEVELOPER_PRIVILEGED_DIAGNOSTIC"
            summaryModel.files.first { it.name.startsWith("privileged/") }.datasetSafe shouldBe false
            summaryModel.files.first { it.name == "status.json" }.datasetSafe shouldBe true
            Files.readString(directory.resolve("privileged/thread-dump-0.txt")) shouldBe
                "PRIVATE_THREAD_STACK_CONTENT"
            Files.exists(directory.resolve("status.json")) shouldBe true
            Files.exists(directory.resolve("artifact-sizes.json")) shouldBe true
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("records missing evidence in bounded required files") {
        val root = Files.createTempDirectory("run-diagnostics-bundle-missing-")
        try {
            val result = DiagnosticBundleWriter(root).write(
                bundleInput(
                    status = null,
                    metrics = ProcessMetricsV1(
                        availability = EvidenceAvailability.MISSING,
                        failureCode = SupervisorFailureCode.METRICS_UNAVAILABLE,
                    ),
                    jvmResults = emptyList(),
                    safeArtifactSizes = emptyList(),
                ),
            )

            val directory = result.bundleDirectory!!
            val summary = Files.readString(directory.resolve("summary.json"))
            Files.readString(directory.resolve("status.json")) shouldContain "MISSING"
            Files.exists(directory.resolve("status.json")) shouldBe true
            Files.exists(directory.resolve("artifact-sizes.json")) shouldBe true
            Files.exists(directory.resolve("recent-stages.json")) shouldBe true
            Files.readString(directory.resolve("artifact-sizes.json")) shouldContain "NOT_CONFIGURED"
            result.summary!!.files.map { it.name } shouldContain "status.json"
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("enforces bounded bundle retention") {
        val root = Files.createTempDirectory("run-diagnostics-retention-")
        try {
            val writer = DiagnosticBundleWriter(root, maxDiagnosticBundles = 2)
            writer.write(bundleInput(stallId = "stall-one"))
            writer.write(bundleInput(stallId = "stall-two"))
            writer.write(bundleInput(stallId = "stall-three"))

            Files.list(root.resolve("supervisor-test-run/stalls")).use { stream ->
                stream.filter { Files.isDirectory(it) }.count() shouldBe 2
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("records an oversized optional evidence file instead of writing it unboundedly") {
        val root = Files.createTempDirectory("run-diagnostics-bundle-bound-")
        try {
            val result = DiagnosticBundleWriter(root, maxBundleBytes = 4_096).write(
                bundleInput(
                    jvmResults = listOf(
                        JvmCommandResult(
                            kind = JvmCommandKind.THREAD_PRINT,
                            availability = EvidenceAvailability.AVAILABLE,
                            output = "x".repeat(10_000),
                        ),
                    ),
                ),
            )

            result.failures shouldContain SupervisorFailureCode.BUNDLE_TOO_LARGE
            Files.exists(result.bundleDirectory!!.resolve("privileged/thread-dump-0.txt")) shouldBe false
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})

internal fun bundleInput(
    diagnosticRunId: String = "supervisor-test-run",
    stallId: String = "stall-000001",
    status: com.wingedsheep.rundiagnostics.RunStatusV1? = fixtureStatus(),
    metrics: ProcessMetricsV1 = ProcessMetricsV1(
        availability = EvidenceAvailability.AVAILABLE,
        cpuTimeNanos = 10,
        rssBytes = 20,
        threadCount = 3,
    ),
    jvmResults: List<JvmCommandResult> = emptyList(),
    safeArtifactSizes: List<SafeArtifactSizeV1> = emptyList(),
) = DiagnosticBundleInput(
    diagnosticRunId = diagnosticRunId,
    stallId = stallId,
    createdWallClock = FIXTURE_START,
    trigger = StallTriggerKind.USEFUL_PROGRESS_STALE,
    classification = DiagnosticClassification.SUSPECTED_STALL,
    action = SupervisorAction.CAPTURE_DIAGNOSTICS_AND_CONTINUE,
    status = status,
    metrics = metrics,
    process = ProcessIdentityResult(
        liveness = ProcessLiveness.ALIVE,
        observation = ProcessHandleObservation(FIXTURE_PID, true, FIXTURE_START),
    ),
    recentHistory = emptyList(),
    jvmResults = jvmResults,
    safeArtifactSizes = safeArtifactSizes,
)
