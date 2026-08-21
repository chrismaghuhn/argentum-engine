package com.wingedsheep.engine.registry

import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.DiagnosticKind
import com.wingedsheep.engine.core.DiagnosticSignal

/**
 * Setup-time failure raised at the registry boundary when a deck references no card definition.
 *
 * It remains an [IllegalArgumentException] for callers that already handle the historical setup
 * failure, while [code] gives automation a stable classification without parsing the message.
 */
class CardDefinitionMissingException(
    private val requestedName: String,
) : IllegalArgumentException("Card not found in registry: $requestedName") {
    val code: String = DiagnosticCode.CARD_DEFINITION_MISSING.name
    val diagnosticCode: DiagnosticCode = DiagnosticCode.CARD_DEFINITION_MISSING
    val diagnostic: DiagnosticSignal = DiagnosticSignal(
        kind = DiagnosticKind.UNSUPPORTED_CARD,
        code = diagnosticCode,
    )
}
