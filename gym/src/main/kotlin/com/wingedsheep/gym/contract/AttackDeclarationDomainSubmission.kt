package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.DiagnosticSignal
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.UnsupportedPathFailure
import com.wingedsheep.engine.legalactions.AttackDeclarationDomainSupport
import com.wingedsheep.engine.legalactions.AttackDeclarationDomainValidator
import com.wingedsheep.engine.legalactions.AttackDeclarationValidationResult
import com.wingedsheep.engine.legalactions.LegalAction

/**
 * Trusted Gym boundary for the externally selected attacker declaration.
 *
 * The validator reads only the certificate registered on [LegalAction]. It deliberately does not
 * accept a [com.wingedsheep.engine.state.GameState]; Rules remains responsible for the subsequent
 * stateful Magic legality check when the submitted declaration is executed.
 */
object AttackDeclarationDomainSubmission {

    /** Reject an incomplete combat certificate as an unsupported trusted path. */
    fun requireSupported(action: LegalAction) {
        if (action.action !is DeclareAttackers) return
        if (action.attackDeclarationDomainSupport !is AttackDeclarationDomainSupport.SUPPORTED ||
            action.attackDeclarationDomain == null
        ) {
            throw UnsupportedPathFailure(
                listOf(DiagnosticSignal(DiagnosticCode.ATTACK_DECLARATION_DOMAIN_UNSUPPORTED))
            )
        }
    }

    /** Validate a submitted declaration against the exact certificate snapshot on [action]. */
    fun requireWithinRegisteredDomain(action: LegalAction, submitted: GameAction) {
        val candidate = action.action as? DeclareAttackers ?: return
        requireSupported(action)
        val declaration = submitted as? DeclareAttackers
            ?: throw IllegalArgumentException("Structured action changed its action type")
        val domain = checkNotNull(action.attackDeclarationDomain)

        when (val result = AttackDeclarationDomainValidator.validate(domain, declaration)) {
            AttackDeclarationValidationResult.Accepted -> Unit
            is AttackDeclarationValidationResult.Rejected -> throw IllegalArgumentException(
                "Attack declaration is outside the registered domain: ${result.reason.name}"
            )
        }

        // Keep the candidate use explicit at this seam: the engine's membership check remains the
        // owner of actor/source identity, while this pure validator owns declaration choices.
        check(candidate.playerId == declaration.playerId) {
            "Structured action changed its action actor"
        }
    }
}
