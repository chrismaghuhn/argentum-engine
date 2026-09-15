package com.wingedsheep.gym.trainer.actor

import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

const val ACTOR_SOURCE_BOOTSTRAP_V1_VERSION: Int = 1
const val ACTOR_SOURCE_BOOTSTRAP_V1_SCHEMA_IDENTITY: String =
    "argentum-ml-source-bootstrap@v1"

@Serializable
enum class SourceBootstrapStatusV1 {
    VERIFIED,
    REJECTED,
}

@Serializable
enum class SourceBootstrapFailureCodeV1 {
    HEAD_UNAVAILABLE,
    HEAD_MISMATCH,
    TRACKED_SOURCE_DIRTY,
    REQUIRED_PIN_MISSING,
}

/** Bounded source-control facts collected by a local bootstrap probe. */
@Serializable
data class SourceBootstrapObservationV1(
    val actualHeadCommit: String?,
    val trackedSourceClean: Boolean,
    val requiredPinsPresent: Boolean,
) {
    init {
        require(actualHeadCommit == null || actualHeadCommit.isNotBlank()) {
            "Observed source HEAD must be null or nonblank"
        }
    }
}

/** The only source revision evidence that a local actor may pass to TrustedActorRunner. */
@Serializable
data class SourceBootstrapResultV1(
    val version: Int = ACTOR_SOURCE_BOOTSTRAP_V1_VERSION,
    val schemaIdentity: String = ACTOR_SOURCE_BOOTSTRAP_V1_SCHEMA_IDENTITY,
    val expectedSourceCommit: String,
    val actualRuntimeSourceCommit: String?,
    val headMatchesExpected: Boolean,
    val trackedSourceClean: Boolean,
    val requiredPinsPresent: Boolean,
    val status: SourceBootstrapStatusV1,
    val failureCode: SourceBootstrapFailureCodeV1? = null,
) {
    val verified: Boolean
        get() = status == SourceBootstrapStatusV1.VERIFIED

    init {
        require(version == ACTOR_SOURCE_BOOTSTRAP_V1_VERSION) {
            "Unsupported source-bootstrap version: $version"
        }
        require(schemaIdentity == ACTOR_SOURCE_BOOTSTRAP_V1_SCHEMA_IDENTITY) {
            "Unsupported source-bootstrap schema identity: $schemaIdentity"
        }
        require(expectedSourceCommit.isNotBlank()) {
            "Expected source commit is required"
        }
        require(actualRuntimeSourceCommit == null || actualRuntimeSourceCommit.isNotBlank()) {
            "Actual runtime source commit must be null or nonblank"
        }
        require(headMatchesExpected ==
            (actualRuntimeSourceCommit != null && actualRuntimeSourceCommit == expectedSourceCommit)
        ) {
            "Source-bootstrap HEAD comparison disagrees with revisions"
        }
        require((status == SourceBootstrapStatusV1.VERIFIED) ==
            (headMatchesExpected && trackedSourceClean && requiredPinsPresent)
        ) {
            "Source-bootstrap status disagrees with verification facts"
        }
        require((status == SourceBootstrapStatusV1.VERIFIED) == (failureCode == null)) {
            "Source-bootstrap status and failure code disagree"
        }
    }
}

interface SourceBootstrapProbeV1 {
    fun inspect(): SourceBootstrapObservationV1
}

/** Evaluates the same probe both before and after a local actor build. */
class LocalSourceBootstrapV1(
    private val probe: SourceBootstrapProbeV1,
) {
    fun verify(expectedSourceCommit: String): SourceBootstrapResultV1 {
        require(expectedSourceCommit.isNotBlank()) {
            "Expected source commit is required"
        }
        val observation = probe.inspect()
        val headMatchesExpected = observation.actualHeadCommit == expectedSourceCommit
        val failureCode = when {
            observation.actualHeadCommit == null -> SourceBootstrapFailureCodeV1.HEAD_UNAVAILABLE
            !headMatchesExpected -> SourceBootstrapFailureCodeV1.HEAD_MISMATCH
            !observation.trackedSourceClean -> SourceBootstrapFailureCodeV1.TRACKED_SOURCE_DIRTY
            !observation.requiredPinsPresent -> SourceBootstrapFailureCodeV1.REQUIRED_PIN_MISSING
            else -> null
        }
        return SourceBootstrapResultV1(
            expectedSourceCommit = expectedSourceCommit,
            actualRuntimeSourceCommit = observation.actualHeadCommit,
            headMatchesExpected = headMatchesExpected,
            trackedSourceClean = observation.trackedSourceClean,
            requiredPinsPresent = observation.requiredPinsPresent,
            status = if (failureCode == null) {
                SourceBootstrapStatusV1.VERIFIED
            } else {
                SourceBootstrapStatusV1.REJECTED
            },
            failureCode = failureCode,
        )
    }
}

fun interface SourceBootstrapGitCommandV1 {
    fun run(arguments: List<String>): String?
}

/** Source-control probe usable on local Linux without embedding provider-specific behavior. */
class GitSourceBootstrapProbeV1(
    repositoryRoot: Path,
    requiredPinnedPaths: List<String> = listOf(
        "gradlew",
        "gradle/wrapper/gradle-wrapper.properties",
        "gradle/libs.versions.toml",
    ),
    git: SourceBootstrapGitCommandV1? = null,
) : SourceBootstrapProbeV1 {
    private val repositoryRoot = repositoryRoot.toAbsolutePath().normalize()
    private val git = git ?: ProcessSourceBootstrapGitCommandV1(this.repositoryRoot)
    private val requiredPinnedPaths = requiredPinnedPaths.distinct().sorted()

    init {
        require(Files.isDirectory(repositoryRoot)) {
            "Source-bootstrap repository root must be a directory"
        }
        require(this.requiredPinnedPaths.isNotEmpty()) {
            "At least one repository-authoritative source pin is required"
        }
        this.requiredPinnedPaths.forEach(::requireSafeRelativePath)
    }

    override fun inspect(): SourceBootstrapObservationV1 {
        val actualHeadCommit = git.run(listOf("rev-parse", "HEAD"))
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val trackedStatus = git.run(listOf("status", "--porcelain=v1", "--untracked-files=no"))
        val trackedSourceClean = trackedStatus != null && trackedStatus.isBlank()
        val requiredPinsPresent = requiredPinnedPaths.all { relativePath ->
            val path = repositoryRoot.resolve(relativePath).normalize()
            path.startsWith(repositoryRoot.normalize()) &&
                Files.isRegularFile(path) &&
                git.run(listOf("ls-files", "--error-unmatch", "--", relativePath))
                    ?.trim() == relativePath
        }
        return SourceBootstrapObservationV1(
            actualHeadCommit = actualHeadCommit,
            trackedSourceClean = trackedSourceClean,
            requiredPinsPresent = requiredPinsPresent,
        )
    }

    private fun requireSafeRelativePath(value: String) {
        val path = Path.of(value).normalize()
        require(value.isNotBlank() && !path.isAbsolute &&
            !path.startsWith(Path.of(".."))
        ) {
            "Required source pin path must be relative and below the repository root"
        }
    }
}

private class ProcessSourceBootstrapGitCommandV1(
    private val repositoryRoot: Path,
) : SourceBootstrapGitCommandV1 {
    override fun run(arguments: List<String>): String? {
        val process = try {
            ProcessBuilder(buildList {
                add("git")
                addAll(arguments)
            })
                .directory(repositoryRoot.toFile())
                .redirectErrorStream(true)
                .start()
        } catch (_: Exception) {
            return null
        }
        val output = try {
            String(process.inputStream.readAllBytes(), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            process.destroyForcibly()
            return null
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        return if (process.exitValue() == 0) output.trimEnd('\r', '\n') else null
    }
}

data class LocalActorExecutionRequestV1(
    val assignment: WorkAssignmentV1,
    val executionAttemptIdentity: ExecutionAttemptIdentityV1,
    val sourceRepositoryRoot: Path,
    val requiredPinnedPaths: List<String> = listOf(
        "gradlew",
        "gradle/wrapper/gradle-wrapper.properties",
        "gradle/libs.versions.toml",
    ),
    val preflight: () -> StoragePreflightResultV1,
    val episodeExecutor: ActorEpisodeExecutor,
    val sinkFactory: () -> B2TrajectorySink,
    val statusSink: ActorStatusSink? = null,
    val clock: ActorClock = SystemActorClock,
    val requestedConcurrency: Int = 1,
    val actualConcurrency: Int = 1,
)

/** Local/Linux composition seam; provider adapters remain outside the actor runner. */
object LocalActorExecutionV1 {
    fun run(request: LocalActorExecutionRequestV1): ActorRunResult {
        val expectedSourceCommit = request.assignment.items.first()
            .environmentIdentity.engineCommit
        val bootstrap = runCatching {
            LocalSourceBootstrapV1(
                GitSourceBootstrapProbeV1(
                    repositoryRoot = request.sourceRepositoryRoot,
                    requiredPinnedPaths = request.requiredPinnedPaths,
                ),
            ).verify(expectedSourceCommit)
        }.getOrNull()
        return TrustedActorRunner(
            assignment = request.assignment,
            executionAttemptIdentity = request.executionAttemptIdentity,
            actualRuntimeSourceCommit = bootstrap?.actualRuntimeSourceCommit
                ?.takeIf { bootstrap.verified },
            preflight = request.preflight,
            episodeExecutor = request.episodeExecutor,
            sinkFactory = request.sinkFactory,
            statusSink = request.statusSink,
            clock = request.clock,
            requestedConcurrency = request.requestedConcurrency,
            actualConcurrency = request.actualConcurrency,
        ).run()
    }
}
