package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.sdk.core.Color

/**
 * Trusted Gym boundary for a caller-supplied mana-color choice.
 *
 * The Rules [LegalAction] owns the candidate domain. This validator only checks the submitted
 * choice against that already-resolved snapshot; it never resolves a [ManaColorSet] or reads
 * game state. Legacy Rules callers retain their historical executor behavior outside this seam.
 */
object ManaColorDomainSubmission {

    /** Reject malformed Rules metadata before a submitted action is materialized or executed. */
    fun requireSupported(action: LegalAction) {
        if (!action.requiresManaColorChoice) {
            require(action.availableManaColors == null) {
                "LegalAction publishes a mana-color domain without requiring a color choice"
            }
            return
        }

        val colors = action.availableManaColors ?: return
        require(colors.distinct().size == colors.size) {
            "LegalAction mana-color domain contains duplicate colors"
        }
    }

    /** Validate a caller-completed action against the exact registered Rules color domain. */
    fun requireWithinRegisteredDomain(action: LegalAction, submitted: GameAction) {
        requireSupported(action)
        val candidate = action.action as? ActivateAbility
        val submittedAbility = submitted as? ActivateAbility

        if (!action.requiresManaColorChoice) {
            require(submittedAbility?.manaColorChoice == null) {
                "Structured action carries an unexpected mana-color choice"
            }
            return
        }

        require(candidate != null && submittedAbility != null) {
            "Structured action changed its action type"
        }
        val selectedColor = submittedAbility.manaColorChoice
            ?: throw IllegalArgumentException("Mana-color choice is required for this action")
        require(selectedColor in normalizedDomain(action)) {
            "Mana-color choice $selectedColor is outside the registered domain: ${normalizedDomain(action)}"
        }
    }

    /** Reject a registered color domain that no longer matches the current Rules candidate. */
    fun requireCurrentDomain(candidate: LegalAction, current: LegalAction) {
        requireSupported(candidate)
        requireSupported(current)
        require(
            candidate.requiresManaColorChoice == current.requiresManaColorChoice &&
                normalizedDomain(candidate) == normalizedDomain(current),
        ) {
            "Registered mana-color domain is stale for the current action"
        }
    }

    private fun normalizedDomain(action: LegalAction): List<Color> = if (
        action.requiresManaColorChoice
    ) {
        (action.availableManaColors ?: Color.entries.toList())
            .distinct()
            .sortedBy(Color::ordinal)
    } else {
        emptyList()
    }
}
