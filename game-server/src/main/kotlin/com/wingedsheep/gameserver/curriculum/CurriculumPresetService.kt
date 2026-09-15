package com.wingedsheep.gameserver.curriculum

import com.wingedsheep.gameserver.deck.DeckValidator
import com.wingedsheep.sdk.core.DeckFormat
import org.springframework.stereotype.Component

/**
 * Loads and validates a server-owned curriculum preset before any runtime identity or lobby is
 * created. The returned deck maps are transient server setup data; callers never receive them.
 */
data class ValidatedCurriculumPreset(
    val preset: CurriculumAiTournamentPreset,
    val sources: List<CurriculumDeckSourceV1>,
    val provenance: CurriculumMatchProvenanceV1,
)

@Component
class CurriculumPresetService(
    private val sourceLoader: CurriculumDeckSourceLoader,
    private val deckValidator: DeckValidator,
) {
    fun loadValidated(preset: CurriculumAiTournamentPreset): ValidatedCurriculumPreset {
        val sources = preset.sourcePaths.map(sourceLoader::load)
        require(sources.size == 2) {
            "Curriculum preset ${preset.identity} must contain exactly two seats"
        }

        val invalidDecks = sources.mapIndexedNotNull { index, source ->
            val validation = deckValidator.validate(source.asCommanderDeck(), DeckFormat.COMMANDER)
            if (validation.valid) null
            else {
                val reason = validation.errors.joinToString("; ") { it.message }
                "seat ${index + 1} (${source.commander}): $reason"
            }
        }
        require(invalidDecks.isEmpty()) {
            "Curriculum preset ${preset.identity} is invalid: ${invalidDecks.joinToString(" | ")}"
        }

        return ValidatedCurriculumPreset(
            preset = preset,
            sources = sources,
            provenance = CurriculumMatchProvenanceV1(
                presetIdentity = preset.identity,
                sources = sources.map(CurriculumDeckSourceV1::provenance),
            ),
        )
    }
}
