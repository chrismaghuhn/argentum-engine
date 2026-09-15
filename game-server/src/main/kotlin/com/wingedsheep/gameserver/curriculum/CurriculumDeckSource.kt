package com.wingedsheep.gameserver.curriculum

import com.wingedsheep.sdk.model.Deck
import kotlinx.serialization.Serializable
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

/** Repository-relative source identity retained in dev status, without retaining a deck map. */
@Serializable
data class CurriculumSourceProvenanceV1(
    val sourcePath: String,
    val sourceDigest: String,
    val commander: String,
    val cardCount: Int,
)

/** Provenance for a locked curriculum matchup. Deck contents remain transient runtime data. */
@Serializable
data class CurriculumMatchProvenanceV1(
    val presetIdentity: String,
    val sources: List<CurriculumSourceProvenanceV1>,
)

/** One parsed authoritative curriculum artifact. */
data class CurriculumDeckSourceV1(
    val sourcePath: String,
    val sourceDigest: String,
    val commander: String,
    val deckList: Map<String, Int>,
    val cardCount: Int,
) {
    /** The structured Commander shape expected by [com.wingedsheep.gameserver.deck.DeckValidator]. */
    fun asCommanderDeck(): Deck = Deck(
        cards = libraryDeckList().flatMap { (cardName, count) -> List(count) { cardName } },
        commander = commander,
    )

    /** Transient 99-card library map derived from the authoritative 100-row source. */
    fun libraryDeckList(): Map<String, Int> {
        val commanderCount = deckList[commander]
            ?: error("Curriculum source $sourcePath does not contain its commander")
        require(commanderCount > 0) { "Curriculum source $sourcePath has no positive commander count" }
        val library = deckList.toMutableMap()
        if (commanderCount == 1) {
            library.remove(commander)
        } else {
            library[commander] = commanderCount - 1
        }
        return library
    }

    fun provenance(): CurriculumSourceProvenanceV1 = CurriculumSourceProvenanceV1(
        sourcePath = sourcePath,
        sourceDigest = sourceDigest,
        commander = commander,
        cardCount = cardCount,
    )
}

/** Server-owned fixed match identities. Client input may select an identity, never its paths. */
enum class CurriculumAiTournamentPreset(
    val requestId: String,
    val identity: String,
    val sourcePaths: List<String>,
) {
    AKIRI_CHEVILL(
        requestId = "MTG_ML_AKIRI_CHEVILL_COMMANDER",
        identity = "argentum-mtg-ml-akiri-chevill-curriculum@v1",
        sourcePaths = listOf(
            "docs/ml/curriculum/akiri-v0.1.txt",
            "docs/ml/curriculum/chevill-v0.1.txt",
        ),
    );

    companion object {
        fun fromRequest(value: String): CurriculumAiTournamentPreset =
            entries.firstOrNull { it.requestId == value || it.identity == value || it.name == value }
                ?: throw IllegalArgumentException("Unknown AI tournament preset: $value")
    }
}

/**
 * Strict loader for the repository's tab-separated curriculum artifacts.
 *
 * The endpoint supplies only server-owned relative paths. The path constructor exists for focused
 * tests and still enforces that every loaded file stays below its supplied repository root.
 */
@Component
class CurriculumDeckSourceLoader {
    private val repositoryRoot: Path

    constructor() {
        repositoryRoot = discoverRepositoryRoot()
    }

    constructor(repositoryRoot: Path) {
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize()
    }

    fun load(sourcePath: String): CurriculumDeckSourceV1 {
        val relativePath = Path.of(sourcePath)
        require(!relativePath.isAbsolute) {
            "Curriculum source path must be repository-relative: $sourcePath"
        }
        require(relativePath.normalize() == relativePath) {
            "Curriculum source path must not contain traversal segments: $sourcePath"
        }

        val resolvedPath = repositoryRoot.resolve(relativePath).normalize()
        require(resolvedPath.startsWith(repositoryRoot)) {
            "Curriculum source path escapes the repository root: $sourcePath"
        }
        require(Files.isRegularFile(resolvedPath)) {
            "Curriculum source file does not exist: $sourcePath"
        }

        val rawBytes = Files.readAllBytes(resolvedPath)
        val sourceText = decodeUtf8(rawBytes, sourcePath)
        return parse(sourcePath.replace('\\', '/'), rawBytes, sourceText)
    }

    private fun decodeUtf8(rawBytes: ByteArray, sourcePath: String): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(rawBytes))
            .toString()
    } catch (e: Exception) {
        throw IllegalArgumentException("Curriculum source is not valid UTF-8: $sourcePath", e)
    }

    private fun parse(sourcePath: String, rawBytes: ByteArray, sourceText: String): CurriculumDeckSourceV1 {
        var declaredCommander: String? = null
        var commanderHeaderCount = 0
        val rows = mutableListOf<SourceRow>()

        for ((lineIndex, rawLine) in sourceText.split('\n').withIndex()) {
            val line = rawLine.removeSuffix("\r")
            if (line.isBlank()) continue
            if (line.startsWith('#')) {
                val headerCommander = COMMANDER_HEADER.matchEntire(line)?.groupValues?.get(1)
                if (headerCommander != null) {
                    commanderHeaderCount++
                    require(commanderHeaderCount == 1) {
                        "Curriculum source $sourcePath declares multiple commanders"
                    }
                    require(headerCommander.isNotBlank()) {
                        "Curriculum source $sourcePath has a blank commander header at line ${lineIndex + 1}"
                    }
                    declaredCommander = headerCommander
                }
                continue
            }

            val columns = line.split('\t', limit = 5)
            require(columns.size == 4) {
                "Curriculum source $sourcePath has a malformed row at line ${lineIndex + 1}"
            }
            val slot = columns[0].toIntOrNull()
            require(columns[0].length == 3 && slot != null) {
                "Curriculum source $sourcePath has an invalid slot at line ${lineIndex + 1}"
            }
            require(slot == rows.size + 1) {
                "Curriculum source $sourcePath has non-contiguous slot $slot; expected ${rows.size + 1}"
            }
            val primaryRole = columns[1]
            val secondaryRoles = columns[2]
            val cardName = columns[3]
            require(primaryRole.isNotBlank() && secondaryRoles.isNotBlank() && cardName.isNotBlank()) {
                "Curriculum source $sourcePath has a blank field at line ${lineIndex + 1}"
            }
            rows += SourceRow(slot, primaryRole, cardName)
        }

        require(commanderHeaderCount == 1 && declaredCommander != null) {
            "Curriculum source $sourcePath is missing its commander header"
        }
        require(rows.size == REQUIRED_CARD_COUNT) {
            "Curriculum source $sourcePath requires exactly $REQUIRED_CARD_COUNT rows (found ${rows.size})"
        }

        val commanderRows = rows.filter { it.primaryRole == "COMMANDER" }
        require(commanderRows.size == 1) {
            "Curriculum source $sourcePath requires exactly one COMMANDER row (found ${commanderRows.size})"
        }
        require(declaredCommander == commanderRows.single().cardName) {
            "Curriculum source $sourcePath commander header disagrees with its COMMANDER row"
        }

        val deckList = linkedMapOf<String, Int>()
        rows.forEach { row -> deckList[row.cardName] = (deckList[row.cardName] ?: 0) + 1 }
        require(deckList.values.sum() == REQUIRED_CARD_COUNT) {
            "Curriculum source $sourcePath has an invalid derived card count"
        }

        return CurriculumDeckSourceV1(
            sourcePath = sourcePath,
            sourceDigest = sha256(rawBytes),
            commander = declaredCommander,
            deckList = deckList,
            cardCount = rows.size,
        )
    }

    private fun sha256(rawBytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(rawBytes)
        .joinToString(separator = "") { byte -> "%02X".format(byte) }

    private fun discoverRepositoryRoot(): Path {
        var candidate = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (true) {
            if (Files.exists(candidate.resolve("settings.gradle.kts")) || Files.exists(candidate.resolve(".git"))) {
                return candidate
            }
            val parent = candidate.parent ?: break
            if (parent == candidate) break
            candidate = parent
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
    }

    private data class SourceRow(
        val slot: Int,
        val primaryRole: String,
        val cardName: String,
    )

    private companion object {
        const val REQUIRED_CARD_COUNT = 100
        val COMMANDER_HEADER = Regex("^# Commander: (.+)$")
    }
}
