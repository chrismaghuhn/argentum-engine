package com.wingedsheep.engine.core

import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.composite.CompositeEffectExecutor
import com.wingedsheep.engine.handlers.effects.player.SkipNextDrawStepExecutor
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.CompositeEffect
import com.wingedsheep.sdk.scripting.effects.DrawCardsEffect
import com.wingedsheep.sdk.scripting.effects.SkipNextDrawStepEffect
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UnsupportedBoundaryDiagnosticsTest : FunSpec({

    val controller = EntityId("player")
    val context = EffectContext(sourceId = null, controllerId = controller)

    test("explicit unsupported SkipNextDrawStep target emits a typed rule diagnostic") {
        val result = SkipNextDrawStepExecutor().execute(
            GameState(),
            SkipNextDrawStepEffect(EffectTarget.Self),
            context
        )

        result.diagnostics.single() shouldBe DiagnosticSignal(
            kind = DiagnosticKind.UNSUPPORTED_RULE_OR_MECHANIC,
            code = DiagnosticCode.SKIP_NEXT_DRAW_TARGET_UNSUPPORTED
        )
    }

    test("composite execution preserves a diagnostic from a failing child") {
        val signal = DiagnosticSignal(
            kind = DiagnosticKind.UNSUPPORTED_RULE_OR_MECHANIC,
            code = DiagnosticCode.SKIP_NEXT_DRAW_TARGET_UNSUPPORTED
        )
        val executor = CompositeEffectExecutor { state, _, _ ->
            EffectResult.error(state, "unsupported", diagnostics = listOf(signal))
        }

        val result = executor.execute(
            GameState(),
            CompositeEffect(listOf(DrawCardsEffect(1))),
            context
        )

        result.diagnostics shouldBe listOf(signal)
    }

    test("composite execution fails closed even when a diagnostic child reports success") {
        val signal = DiagnosticSignal(
            kind = DiagnosticKind.UNSUPPORTED_RULE_OR_MECHANIC,
            code = DiagnosticCode.SKIP_NEXT_DRAW_TARGET_UNSUPPORTED
        )
        var executions = 0
        val executor = CompositeEffectExecutor { state, _, _ ->
            executions++
            EffectResult.success(state, diagnostics = listOf(signal))
        }

        val result = executor.execute(
            GameState(),
            CompositeEffect(listOf(DrawCardsEffect(1), DrawCardsEffect(2))),
            context
        )

        executions shouldBe 1
        result.error shouldBe "Unsupported path during composite execution"
        result.diagnostics shouldBe listOf(signal)
    }
})
