package com.wingedsheep.rundiagnostics.supervisor

import com.wingedsheep.rundiagnostics.AtomicStatusFile
import com.wingedsheep.rundiagnostics.AtomicStatusFileOps
import com.wingedsheep.rundiagnostics.DiagnosticsRecorder
import com.wingedsheep.rundiagnostics.RunStatusCodec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.AccessMode
import java.nio.file.CopyOption
import java.nio.file.DirectoryStream
import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.FileSystemException
import java.nio.file.FileSystems
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.WatchService
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.spi.FileSystemProvider
import java.nio.channels.SeekableByteChannel

class D3PublicationAndBoundednessVerificationTest : FunSpec({
    test("D3-06 STATUS_WRITE_FAILURE is non-fatal and preserves the old sidecar") {
        val directory = Files.createTempDirectory("run-diagnostics-d3-status-failure-")
        val statusPath = directory.resolve("run-status.json")
        val clock = MutableSupervisorClock()
        val recorder = DiagnosticsRecorder.enabled(
            diagnosticRunId = "d3-status-failure",
            sourceCommit = "test",
            workloadType = "D3_TEST",
            initialStage = fixtureStage(),
            processId = FIXTURE_PID,
            wallClock = FIXTURE_WALL_CLOCK,
            monotonicClock = clock,
        )
        try {
            val oldStatus = recorder.snapshot()!!
            Files.write(statusPath, RunStatusCodec.encode(oldStatus))
            recorder.heartbeatTick()
            recorder.recordUsefulProgress(authoritativeTransitionDelta = 1)
            val publication = AtomicStatusFile(
                statusPath,
                ops = D3AtomicStatusFileOps(
                    replace = { _, _ -> throw IOException("injected atomic replacement failure") },
                ),
            ).publish(RunStatusCodec.encode(recorder.snapshot()!!))

            publication.shouldBeInstanceOf<com.wingedsheep.rundiagnostics.StatusPublicationResult.Failed>()
                .code shouldBe com.wingedsheep.rundiagnostics.StatusPublicationFailureCode.STATUS_ATOMIC_REPLACE_FAILED
            recorder.heartbeatTick() shouldBe 2
            recorder.snapshot()!!.progress.usefulProgressSequence shouldBe 1
            val preserved = StatusSidecarReader(statusPath).read()
                .shouldBeInstanceOf<StatusReadResult.Available>().status
            preserved shouldBe oldStatus

            val sink = D3RecordingBundleSink()
            val fixture = newD3Supervisor(
                statuses = null,
                statusPath = statusPath,
                metrics = listOf(d3Metrics(), d3Metrics()),
                clock = clock,
                bundleSink = sink,
            )
            try {
                fixture.supervisor.pollOnce()
                clock.elapsedNanos = 200_000_000
                val stale = fixture.supervisor.pollOnce()

                stale.process.liveness shouldBe ProcessLiveness.ALIVE
                stale.decision.classification shouldBe DiagnosticClassification.SUSPECTED_STALL
                stale.decision.action shouldBe SupervisorAction.CAPTURE_DIAGNOSTICS_AND_CONTINUE
                sink.inputs.size shouldBe 1
            } finally {
                fixture.close()
            }
        } finally {
            recorder.close()
            directory.toFile().deleteRecursively()
        }
    }

    test("D3-07 PARTIAL_TEMP_FILE leaves the old complete status readable") {
        val directory = Files.createTempDirectory("run-diagnostics-d3-partial-status-")
        try {
            val statusPath = directory.resolve("run-status.json")
            val oldBytes = RunStatusCodec.encode(d3Status())
            Files.write(statusPath, oldBytes)
            val result = AtomicStatusFile(
                statusPath,
                ops = D3AtomicStatusFileOps(
                    write = { path, bytes ->
                        Files.write(path, bytes.copyOf(3), WRITE, TRUNCATE_EXISTING)
                        throw IOException("injected partial write")
                    },
                ),
            ).publish(RunStatusCodec.encode(d3Status(heartbeatSequence = 2)))

            result.shouldBeInstanceOf<com.wingedsheep.rundiagnostics.StatusPublicationResult.Failed>()
                .code shouldBe com.wingedsheep.rundiagnostics.StatusPublicationFailureCode.STATUS_WRITE_FAILED
            Files.readAllBytes(statusPath) shouldBe oldBytes
            StatusSidecarReader(statusPath).read()
                .shouldBeInstanceOf<StatusReadResult.Available>().status shouldBe d3Status()
            Files.list(directory).use { stream ->
                stream.filter { it.fileName.toString() != "run-status.json" }.count() shouldBe 0
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    test("D3-14 unsupported atomic replacement is explicit and never falls back") {
        val directory = Files.createTempDirectory("run-diagnostics-d3-unsupported-atomic-")
        try {
            val statusPath = directory.resolve("run-status.json")
            val oldBytes = RunStatusCodec.encode(d3Status())
            Files.write(statusPath, oldBytes)
            val result = AtomicStatusFile(
                statusPath,
                ops = D3AtomicStatusFileOps(
                    replace = { source, target ->
                        throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "injected")
                    },
                ),
            ).publish(RunStatusCodec.encode(d3Status(heartbeatSequence = 2)))

            val failed = result.shouldBeInstanceOf<com.wingedsheep.rundiagnostics.StatusPublicationResult.Failed>()
            failed.code shouldBe com.wingedsheep.rundiagnostics.StatusPublicationFailureCode.STATUS_ATOMIC_REPLACE_UNAVAILABLE
            failed.implementation shouldBe com.wingedsheep.rundiagnostics.AtomicReplacementImplementation.PROVIDER_UNSUPPORTED
            Files.readAllBytes(statusPath) shouldBe oldBytes
            StatusSidecarReader(statusPath).read()
                .shouldBeInstanceOf<StatusReadResult.Available>().status shouldBe d3Status()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    test("bundle summaries keep JVM text privileged and exclude semantic payload fields") {
        val root = Files.createTempDirectory("run-diagnostics-d3-privacy-")
        try {
            val result = DiagnosticBundleWriter(root).write(
                bundleInput(
                    jvmResults = listOf(
                        JvmCommandResult(JvmCommandKind.THREAD_PRINT, EvidenceAvailability.AVAILABLE, output = "PRIVATE_THREAD_STACK"),
                        JvmCommandResult(JvmCommandKind.GC_HEAP_INFO, EvidenceAvailability.AVAILABLE, output = "PRIVATE_HEAP_INFO"),
                        JvmCommandResult(JvmCommandKind.VM_FLAGS, EvidenceAvailability.AVAILABLE, output = "PRIVATE_VM_FLAGS"),
                    ),
                ),
            )
            val summary = result.summary!!
            val summaryText = Files.readString(result.bundleDirectory!!.resolve("summary.json")).lowercase()
            val statusText = RunStatusCodec.encode(d3Status()).toString(Charsets.UTF_8).lowercase()
            val forbidden = listOf(
                "gamestate",
                "playerobservation",
                "completelegaldomain",
                "rawaction",
                "chosenaction",
                "reward",
                "hiddenhand",
                "librarycontents",
                "facedown",
                "exilecontents",
            )

            forbidden.forEach { token ->
                summaryText shouldNotContain token
                statusText shouldNotContain token
            }
            summary.privilegedDiagnosticPolicy shouldBe "DEVELOPER_PRIVILEGED_DIAGNOSTIC_NOT_DATASET_SAFE"
            summary.files.filter { it.name.startsWith("privileged/") }
                .all { !it.datasetSafe } shouldBe true
            Files.readString(result.bundleDirectory.resolve("privileged/thread-dump-0.txt")) shouldBe
                "PRIVATE_THREAD_STACK"
            Files.readString(result.bundleDirectory.resolve("privileged/heap-info.txt")) shouldBe
                "PRIVATE_HEAP_INFO"
            Files.readString(result.bundleDirectory.resolve("privileged/vm-flags.txt")) shouldBe
                "PRIVATE_VM_FLAGS"
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("bundle byte bounds reject oversized privileged evidence without writing it") {
        val root = Files.createTempDirectory("run-diagnostics-d3-bundle-bound-")
        try {
            val result = DiagnosticBundleWriter(root, maxBundleBytes = 4_096).write(
                bundleInput(
                    jvmResults = listOf(
                        JvmCommandResult(
                            kind = JvmCommandKind.THREAD_PRINT,
                            availability = EvidenceAvailability.AVAILABLE,
                            output = "x".repeat(20_000),
                        ),
                    ),
                ),
            )
            val directory = result.bundleDirectory!!
            result.failures shouldContain SupervisorFailureCode.BUNDLE_TOO_LARGE
            Files.exists(directory.resolve("privileged/thread-dump-0.txt")) shouldBe false
            Files.walk(directory).use { stream ->
                (stream.filter { Files.isRegularFile(it) }.mapToLong { path -> Files.size(path) }.sum() <= 4_096L) shouldBe true
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("retention deletes only old run-owned bundles and keeps unrelated files") {
        val root = Files.createTempDirectory("run-diagnostics-d3-retention-scope-")
        try {
            val stalls = root.resolve("stalls")
            Files.createDirectories(stalls.resolve("stall-000001"))
            Files.createDirectories(stalls.resolve("stall-000002"))
            Files.writeString(stalls.resolve("unrelated.txt"), "keep")

            val result = DiagnosticRetention(stalls, maxDiagnosticBundles = 1).enforce()

            result.availability shouldBe EvidenceAvailability.AVAILABLE
            result.deletedBundleCount shouldBe 1
            Files.exists(stalls.resolve("stall-000001")) shouldBe false
            Files.exists(stalls.resolve("stall-000002")) shouldBe true
            Files.readString(stalls.resolve("unrelated.txt")) shouldBe "keep"
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("retention failure is explicit and leaves all files untouched") {
        val root = Files.createTempDirectory("run-diagnostics-d3-retention-failure-")
        try {
            val stalls = root.resolve("stalls")
            Files.createDirectories(stalls.resolve("stall-000001"))
            Files.createDirectories(stalls.resolve("stall-000002"))
            Files.writeString(stalls.resolve("unrelated.txt"), "keep")
            val before = Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }.map { it.toString() }.toList().sorted()
            }
            val failingPath = D3FailingRetentionFileSystem(stalls).path

            val result = DiagnosticRetention(failingPath, maxDiagnosticBundles = 1).enforce()

            result.availability shouldBe EvidenceAvailability.FAILED
            result.failureCode shouldBe SupervisorFailureCode.RETENTION_FAILED
            result.deletedBundleCount shouldBe 0
            val after = Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }.map { it.toString() }.toList().sorted()
            }
            after shouldBe before
            Files.exists(stalls.resolve("stall-000001")) shouldBe true
            Files.exists(stalls.resolve("stall-000002")) shouldBe true
            Files.readString(stalls.resolve("unrelated.txt")) shouldBe "keep"
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("diagnostics history remains bounded without workload imports") {
        val recorder = DiagnosticsRecorder.enabled(
            diagnosticRunId = "d3-history-bound",
            sourceCommit = "test",
            workloadType = "D3_TEST",
            initialStage = fixtureStage(),
            processId = FIXTURE_PID,
            wallClock = FIXTURE_WALL_CLOCK,
            monotonicClock = MutableSupervisorClock(),
            historyCapacity = 2,
        )
        try {
            repeat(8) { recorder.heartbeatTick() }

            recorder.recentHistory().size shouldBe 2
            recorder.recentHistory().map { it.kind }.distinct() shouldBe
                listOf(com.wingedsheep.rundiagnostics.ProgressHistoryKind.HEARTBEAT)
        } finally {
            recorder.close()
        }
    }
})

private class D3AtomicStatusFileOps(
    private val write: ((Path, ByteArray) -> Unit)? = null,
    private val replace: ((Path, Path) -> Unit)? = null,
) : AtomicStatusFileOps {
    private val delegate = AtomicStatusFileOps.system()

    override fun createTempFile(directory: Path, prefix: String, suffix: String): Path =
        delegate.createTempFile(directory, prefix, suffix)

    override fun writeAndForce(path: Path, bytes: ByteArray) {
        write?.invoke(path, bytes) ?: delegate.writeAndForce(path, bytes)
    }

    override fun atomicReplace(source: Path, target: Path) {
        replace?.invoke(source, target) ?: delegate.atomicReplace(source, target)
    }

    override fun deleteIfExists(path: Path) {
        delegate.deleteIfExists(path)
    }
}

private class D3FailingRetentionFileSystem(stallsDirectory: Path) {
    private val provider = D3FailingRetentionProvider(stallsDirectory)
    private val fileSystem = D3RetentionFileSystem(provider)
    val path: Path = D3RetentionPath(stallsDirectory, fileSystem)

    init {
        provider.fileSystem = fileSystem
    }
}

private class D3FailingRetentionProvider(
    private val failingDirectory: Path,
) : FileSystemProvider() {
    private val delegate = FileSystems.getDefault().provider()
    lateinit var fileSystem: FileSystem

    override fun getScheme(): String = "d3-failing"

    override fun newFileSystem(uri: URI, env: MutableMap<String, *>): FileSystem = fileSystem

    override fun getFileSystem(uri: URI): FileSystem = fileSystem

    override fun getPath(uri: URI): Path = D3RetentionPath(Path.of(uri), fileSystem)

    override fun newByteChannel(
        path: Path,
        options: MutableSet<out OpenOption>,
        vararg attrs: FileAttribute<*>,
    ): SeekableByteChannel = delegate.newByteChannel(unwrap(path), options, *attrs)

    override fun newDirectoryStream(
        dir: Path,
        filter: DirectoryStream.Filter<in Path>,
    ): DirectoryStream<Path> {
        if (unwrap(dir).toAbsolutePath().normalize() == failingDirectory.toAbsolutePath().normalize()) {
            throw FileSystemException("injected retention listing failure")
        }
        return delegate.newDirectoryStream(unwrap(dir), filter)
    }

    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) =
        delegate.createDirectory(unwrap(dir), *attrs)

    override fun delete(path: Path) = delegate.delete(unwrap(path))

    override fun copy(source: Path, target: Path, vararg options: CopyOption) =
        delegate.copy(unwrap(source), unwrap(target), *options)

    override fun move(source: Path, target: Path, vararg options: CopyOption) =
        delegate.move(unwrap(source), unwrap(target), *options)

    override fun isSameFile(path: Path, path2: Path): Boolean =
        delegate.isSameFile(unwrap(path), unwrap(path2))

    override fun isHidden(path: Path): Boolean = delegate.isHidden(unwrap(path))

    override fun getFileStore(path: Path): FileStore = delegate.getFileStore(unwrap(path))

    override fun checkAccess(path: Path, vararg modes: AccessMode) =
        delegate.checkAccess(unwrap(path), *modes)

    override fun <V : FileAttributeView> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption,
    ): V? = delegate.getFileAttributeView(unwrap(path), type, *options)

    override fun <A : java.nio.file.attribute.BasicFileAttributes> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption,
    ): A = delegate.readAttributes(unwrap(path), type, *options)

    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: LinkOption,
    ): MutableMap<String, Any> = delegate.readAttributes(unwrap(path), attributes, *options)

    override fun setAttribute(
        path: Path,
        attribute: String,
        value: Any?,
        vararg options: LinkOption,
    ) = delegate.setAttribute(unwrap(path), attribute, value, *options)

    private fun unwrap(path: Path): Path =
        (path as? D3RetentionPath)?.delegate ?: path
}

private class D3RetentionFileSystem(
    private val retentionProvider: D3FailingRetentionProvider,
) : FileSystem() {
    private val delegate = FileSystems.getDefault()

    override fun provider(): FileSystemProvider = retentionProvider

    override fun close() = Unit

    override fun isOpen(): Boolean = true

    override fun isReadOnly(): Boolean = delegate.isReadOnly

    override fun getSeparator(): String = delegate.separator

    override fun getRootDirectories(): Iterable<Path> = delegate.rootDirectories

    override fun getFileStores(): Iterable<FileStore> = delegate.fileStores

    override fun supportedFileAttributeViews(): Set<String> = delegate.supportedFileAttributeViews()

    override fun getPath(first: String, vararg more: String): Path =
        D3RetentionPath(delegate.getPath(first, *more), this)

    override fun getPathMatcher(syntaxAndPattern: String): java.nio.file.PathMatcher =
        delegate.getPathMatcher(syntaxAndPattern)

    override fun getUserPrincipalLookupService(): UserPrincipalLookupService =
        delegate.userPrincipalLookupService

    override fun newWatchService(): WatchService = delegate.newWatchService()
}

private class D3RetentionPath(
    val delegate: Path,
    private val retentionFileSystem: FileSystem,
) : Path by delegate {
    override fun getFileSystem(): FileSystem = retentionFileSystem

    override fun toAbsolutePath(): Path = D3RetentionPath(delegate.toAbsolutePath(), retentionFileSystem)

    override fun normalize(): Path = D3RetentionPath(delegate.normalize(), retentionFileSystem)
}
