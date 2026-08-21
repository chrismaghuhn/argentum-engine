package com.wingedsheep.engine.core

import com.wingedsheep.engine.registry.CardDefinitionMissingException
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ExecutionDiagnosticsTest : FunSpec({

    test("ExecutionResult diagnostics survive EffectResult conversion but stay off the wire") {
        val signal = DiagnosticSignal(
            kind = DiagnosticKind.UNSUPPORTED_RULE_OR_MECHANIC,
            code = DiagnosticCode.PREVENT_DAMAGE_CONFIGURATION_UNSUPPORTED
        )
        val result = ExecutionResult.success(GameState()).copy(diagnostics = listOf(signal))

        val roundTripped = EffectResult.from(result).toExecutionResult()

        roundTripped.diagnostics shouldBe listOf(signal)
        Json { encodeDefaults = true; allowStructuredMapKeys = true }
            .encodeToString(ExecutionResult.serializer(), result)
            .shouldNotContain("diagnostics")
    }

    test("missing card setup is a typed IllegalArgumentException with a stable code") {
        val failure = shouldThrow<CardDefinitionMissingException> {
            CardRegistry().requireCard("missing-card")
        }

        (failure is IllegalArgumentException) shouldBe true
        failure.code shouldBe DiagnosticCode.CARD_DEFINITION_MISSING.name
        failure.diagnostic shouldBe DiagnosticSignal(
            kind = DiagnosticKind.UNSUPPORTED_CARD,
            code = DiagnosticCode.CARD_DEFINITION_MISSING,
        )
    }
})
