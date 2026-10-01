package com.wingedsheep.engine.handlers.effects.mana

import com.wingedsheep.engine.core.EffectResult
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.handlers.DynamicAmountEvaluator
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.EffectExecutor
import com.wingedsheep.engine.mechanics.mana.capturedProductionSourceSubtypes
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.sdk.scripting.effects.AddColorlessManaEffect
import com.wingedsheep.sdk.scripting.effects.ManaRestriction
import kotlin.reflect.KClass

/**
 * Executor for AddColorlessManaEffect.
 * "Add {C}{C}" or "Add an amount of {C} equal to..."
 */
class AddColorlessManaExecutor(
    private val amountEvaluator: DynamicAmountEvaluator
) : EffectExecutor<AddColorlessManaEffect> {

    override val effectType: KClass<AddColorlessManaEffect> = AddColorlessManaEffect::class

    override fun execute(
        state: GameState,
        effect: AddColorlessManaEffect,
        context: EffectContext
    ): EffectResult {
        val amount = amountEvaluator.evaluate(state, effect.amount, context)
        if (amount <= 0) {
            return EffectResult.success(state)
        }

        if (effect.restriction == null && effect.riders.isEmpty()) {
            return EffectResult.success(
                ManaProvenanceTracker.addUnrestrictedMana(
                    state = state,
                    playerId = context.controllerId,
                    sourceId = context.sourceId,
                    color = PaymentManaColor.COLORLESS,
                    amount = amount,
                    sourceSubtypes = context.capturedProductionSourceSubtypes(),
                )
            )
        }

        val newState = state.updateEntity(context.controllerId) { container ->
            val manaPool = container.get<ManaPoolComponent>() ?: ManaPoolComponent()
            // Riders ride on restricted-mana entries, so rider-carrying mana with no restriction is
            // stored under the no-op AnySpend marker (mirrors AddManaExecutor).
            val updatedPool = manaPool.addRestricted(
                null,
                amount,
                effect.restriction ?: ManaRestriction.AnySpend,
                effect.riders,
            )
            container.with(updatedPool)
        }

        return EffectResult.success(
            ManaProvenanceTracker.tagAddedRestrictedMana(
                newState,
                context.controllerId,
                context.sourceId,
                amount,
                sourceSubtypes = context.capturedProductionSourceSubtypes(),
            )
        )
    }
}
