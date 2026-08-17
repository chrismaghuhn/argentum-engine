package com.wingedsheep.ai.engine

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.ManaSymbol
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import com.wingedsheep.sdk.scripting.AlternativePaymentChoice
import com.wingedsheep.sdk.scripting.ConvokePayment

/**
 * Materializes deterministic payments advertised by a [LegalAction].
 *
 * The legal-action boundary exposes payment domains as metadata so a client can let a human
 * choose. Non-interactive AI and Gym selectors still need a safe default: an action that is marked
 * affordable because it can use convoke, improvise, waterbend, or an additional-cost choice must
 * carry that choice into the submitted [GameAction].
 */
object AutomaticPaymentSelection {

    /** Return [action]'s engine action with the first deterministic payment choices filled in. */
    fun fill(action: LegalAction): GameAction {
        val gameAction = withAutomaticTapForGeneric(action, withAutomaticConvoke(action))
        val info = action.additionalCostInfo ?: return gameAction
        val existing = when (gameAction) {
            is CastSpell -> gameAction.additionalCostPayment
            is ActivateAbility -> gameAction.costPayment
            else -> null
        } ?: AdditionalCostPayment()
        val payment = when (info.costType) {
            "Blight" -> existing.copy(blightTargets = info.validBlightTargets.take(1))
            "Behold" -> existing.copy(beheldCards = info.validBeholdTargets.take(info.beholdCount))
            "TapPermanents" -> existing.copy(tappedPermanents = info.validTapTargets.take(info.tapCount))
            "DiscardCard" -> existing.copy(discardedCards = info.validDiscardTargets.take(info.discardCount))
            "SacrificePermanent" -> existing.copy(
                sacrificedPermanents = info.validSacrificeTargets.take(info.sacrificeCount)
            )
            "BouncePermanent" -> existing.copy(bouncedPermanents = info.validBounceTargets.take(info.bounceCount))
            "ExileFromGraveyard" -> existing.copy(exiledCards = info.validExileTargets.take(info.exileMinCount))
            "TapForTotalPower" -> {
                val required = info.tapForPowerRequired
                val contributors = info.tapForPowerCreatures.filter { it.power > 0 }
                val cheapestSolo = contributors.filter { it.power >= required }.minByOrNull { it.power }
                if (cheapestSolo != null) {
                    existing.copy(variableCostPermanents = listOf(cheapestSolo.entityId))
                } else {
                    val chosen = mutableListOf<EntityId>()
                    var total = 0
                    for (creature in contributors.sortedByDescending { it.power }) {
                        if (total >= required) break
                        chosen += creature.entityId
                        total += creature.power
                    }
                    if (total < required) {
                        return when (gameAction) {
                            is CastSpell -> gameAction.copy(declaredCostSlot = null)
                            else -> gameAction
                        }
                    }
                    existing.copy(variableCostPermanents = chosen)
                }
            }
            else -> return gameAction
        }
        return when (gameAction) {
            is CastSpell -> gameAction.copy(additionalCostPayment = payment)
            is ActivateAbility -> gameAction.copy(costPayment = payment)
            else -> gameAction
        }
    }

    /** Fill the convoke creatures the cast handler consumes, colored pips first. */
    private fun withAutomaticConvoke(action: LegalAction): GameAction {
        val cast = action.action as? CastSpell ?: return action.action
        val creatures = action.convokeCreatures.orEmpty()
        val costString = action.manaCostString
        if (!action.hasConvoke || creatures.isEmpty() || costString == null) return cast

        val cost = ManaCost.parse(costString)
        val coloredNeeded = cost.symbols
            .filterIsInstance<ManaSymbol.Colored>()
            .groupingBy { it.color }
            .eachCount()
            .toMutableMap()
        var genericNeeded = cost.genericAmount
        val payments = linkedMapOf<EntityId, ConvokePayment>()
        val unused = creatures.toMutableList()

        for ((color, count) in coloredNeeded) {
            repeat(count) {
                val index = unused.indexOfFirst { color in it.colors }
                if (index >= 0) {
                    val creature = unused.removeAt(index)
                    payments[creature.entityId] = ConvokePayment(color)
                }
            }
        }
        while (genericNeeded > 0 && unused.isNotEmpty()) {
            val creature = unused.removeAt(0)
            payments[creature.entityId] = ConvokePayment()
            genericNeeded--
        }
        if (payments.isEmpty()) return cast

        val existing = cast.alternativePayment ?: AlternativePaymentChoice.NONE
        return cast.copy(
            alternativePayment = existing.copy(
                convokedCreatures = existing.convokedCreatures + payments
            )
        )
    }

    /** Fill the safe tap-for-generic payment when the enumerator says it is required. */
    private fun withAutomaticTapForGeneric(action: LegalAction, gameAction: GameAction): GameAction {
        if (!action.hasTapForGeneric) return gameAction
        if (action.tapForGenericRequired == false) return gameAction
        val cast = gameAction as? CastSpell ?: return gameAction
        val artifacts = action.tapForGenericPermanents.orEmpty().filterNot { it.isCreature }
        if (artifacts.isEmpty()) return gameAction
        val costString = action.manaCostString ?: return gameAction

        val genericInCost = ManaCost.parse(costString).genericAmount
        val cap = minOf(action.tapForGenericAmount ?: genericInCost, genericInCost)
        if (cap <= 0) return gameAction

        val existing = cast.alternativePayment ?: AlternativePaymentChoice.NONE
        return cast.copy(
            alternativePayment = existing.copy(
                tapForGenericPermanents = artifacts.take(cap).mapTo(linkedSetOf()) { it.entityId }
            )
        )
    }
}
