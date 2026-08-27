package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.DiagnosticSignal
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.UnsupportedPathFailure
import com.wingedsheep.engine.legalactions.BlockerDeclarationDomainSupport
import com.wingedsheep.engine.legalactions.BlockerDeclarationDomainValidator
import com.wingedsheep.engine.legalactions.BlockerDeclarationValidationResult
import com.wingedsheep.engine.legalactions.LegalAction

/**
 * Trusted Gym boundary for an externally selected blocker declaration.
 *
 * The pure validator consumes only the exact Rules certificate registered on [LegalAction]. It
 * does not inspect GameState and does not reimplement combat legality. ActionProcessor/Rules still
 * performs the stateful execution check after this boundary.
 */
object BlockerDeclarationDomainSubmission {

    fun requireSupported(action: LegalAction) {
        if (action.action !is DeclareBlockers) return
        if (action.blockerDeclarationDomainSupport !is BlockerDeclarationDomainSupport.SUPPORTED ||
            action.blockerDeclarationDomain == null
        ) {
            throw UnsupportedPathFailure(
                listOf(DiagnosticSignal(DiagnosticCode.BLOCKER_DECLARATION_DOMAIN_UNSUPPORTED))
            )
        }
    }

    fun requireWithinRegisteredDomain(action: LegalAction, submitted: GameAction) {
        val candidate = action.action as? DeclareBlockers ?: return
        requireSupported(action)
        val declaration = submitted as? DeclareBlockers
            ?: throw IllegalArgumentException("Structured action changed its action type")
        require(candidate.playerId == declaration.playerId) {
            "Structured action changed its action actor"
        }

        when (val result = BlockerDeclarationDomainValidator.validate(
            checkNotNull(action.blockerDeclarationDomain),
            declaration,
        )) {
            BlockerDeclarationValidationResult.Accepted -> Unit
            is BlockerDeclarationValidationResult.Rejected -> throw IllegalArgumentException(
                "Blocker declaration is outside the registered domain: ${result.reason.name}"
            )
        }
    }
}
