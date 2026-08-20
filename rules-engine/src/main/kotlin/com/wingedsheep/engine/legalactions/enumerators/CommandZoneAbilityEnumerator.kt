package com.wingedsheep.engine.legalactions.enumerators

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.legalactions.ActionEnumerator
import com.wingedsheep.engine.legalactions.AdditionalCostData
import com.wingedsheep.engine.legalactions.EnumerationContext
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.scripting.AbilityCost
import com.wingedsheep.sdk.scripting.ActivationRestriction
import com.wingedsheep.sdk.scripting.TimingRule
import com.wingedsheep.sdk.scripting.costs.CostAtom
import com.wingedsheep.sdk.scripting.costs.manaCostOrNull

/**
 * Enumerates activated abilities on cards in the player's command zone.
 *
 * The motivating case is the Momir Basic Vanguard avatar
 * ([com.wingedsheep.sdk.core.Format.MomirBasic]), which grants "{X}{X}{X}, Discard a card: …"
 * with `activateFromZone == Zone.COMMAND`. Modeled on [ZoneActivatedAbilityEnumerator]: scan a
 * non-battlefield zone, filter abilities by `activateFromZone`, gate sorcery timing on
 * [EnumerationContext.canPlaySorcerySpeed], honour activation restrictions, and surface
 * mana/discard cost payability plus X-cost info. [com.wingedsheep.engine.handlers.actions.ability.ActivateAbilityHandler]
 * already accepts non-battlefield `activateFromZone` generically (owner + zone-membership check),
 * so no handler change is needed.
 */
class CommandZoneAbilityEnumerator : ActionEnumerator {

    override fun enumerate(context: EnumerationContext): List<LegalAction> {
        val result = mutableListOf<LegalAction>()
        val state = context.state
        val playerId = context.playerId

        for (entityId in state.getZone(playerId, Zone.COMMAND)) {
            val container = state.getEntity(entityId) ?: continue
            val cardComponent = container.get<CardComponent>() ?: continue

            val cardDef = context.cardRegistry.getCard(cardComponent.name) ?: continue
            val commandAbilities = cardDef.script.activatedAbilities.filter {
                it.activateFromZone == Zone.COMMAND
            }

            for (ability in commandAbilities) {
                // "Activate only as a sorcery" — mirror the graveyard/battlefield gate so the
                // action is never offered at instant speed (the handler would reject it anyway).
                if (ability.timing == TimingRule.SorcerySpeed && !context.canPlaySorcerySpeed) continue

                // Activation restrictions (e.g. once each turn).
                if (ability.restrictions.any {
                        !context.castPermissionUtils.checkActivationRestriction(state, playerId, it, entityId, ability.id, ability.isExhaust)
                    }
                ) continue

                // Cost payability — Mana, Discard, and PayLife, atom or composite (the avatar's
                // "{X}{X}{X}, Discard a card"). Other atoms are validated by the handler at
                // payment time.
                val effectiveCost = ability.cost
                val handCards = state.getZone(playerId, Zone.HAND)
                val abilityContext = com.wingedsheep.engine.mechanics.mana.buildAbilityPaymentContext(
                    cardComponent, context.projected, entityId, ability
                )
                var costCanBePaid = true
                var hasDiscardCost = false
                val resolvedPayLifeTotal = context.costUtils.resolvePayLifeCostTotal(
                    state, playerId, entityId, effectiveCost
                ) ?: continue
                if (state.lifeTotal(playerId) < resolvedPayLifeTotal) continue

                val aggregateManaCost = when (effectiveCost) {
                    is AbilityCost.Atom -> effectiveCost.manaCostOrNull ?: ManaCost.ZERO
                    is AbilityCost.Composite -> effectiveCost.costs
                        .mapNotNull { it.manaCostOrNull }
                        .fold(ManaCost.ZERO) { total, manaCost -> total + manaCost }
                    else -> ManaCost.ZERO
                }
                if (aggregateManaCost.cmc > 0 && !context.manaSolver.canPay(
                        state,
                        playerId,
                        aggregateManaCost,
                        precomputedSources = context.availableManaSources,
                        spellContext = abilityContext,
                        additionalPayLife = resolvedPayLifeTotal,
                    )
                ) costCanBePaid = false

                fun checkAtom(atom: CostAtom) {
                    when (atom) {
                        // The aggregate mana check above owns every mana atom and the complete
                        // PayLife total. X is still gated by maxAffordableX below.
                        is CostAtom.Mana -> {}
                        is CostAtom.Discard -> {
                            hasDiscardCost = true
                            if (handCards.size < atom.count) costCanBePaid = false
                        }
                        // PayLife was resolved as the complete cost total above.
                        is CostAtom.PayLife -> {}
                        else -> {}
                    }
                }

                when (effectiveCost) {
                    is AbilityCost.Atom -> checkAtom(effectiveCost.atom)
                    is AbilityCost.Composite -> effectiveCost.costs.forEach { sub ->
                        (sub as? AbilityCost.Atom)?.let { checkAtom(it.atom) }
                    }
                    else -> {}
                }
                if (!costCanBePaid) continue

                val costInfo = if (hasDiscardCost) {
                    AdditionalCostData(
                        description = "Discard a card",
                        costType = "DiscardCard",
                        validDiscardTargets = handCards,
                        discardCount = 1
                    )
                } else null

                val abilityManaCost = when (effectiveCost) {
                    is AbilityCost.Atom -> effectiveCost.manaCostOrNull
                    is AbilityCost.Composite -> effectiveCost.costs.firstNotNullOfOrNull { it.manaCostOrNull }
                    else -> null
                }
                val hasXCost = abilityManaCost?.hasX == true
                val maxAffordableX = if (hasXCost) {
                    context.costUtils.calculateMaxAffordableX(
                        state, playerId, ability.cost, abilityManaCost,
                        precomputedSources = context.availableManaSources,
                        // Same source scoping the battlefield enumerator passes: cost filters
                        // routinely resolve against the ability's own permanent.
                        sourceId = entityId
                    )
                } else null

                result.add(
                    LegalAction(
                        actionType = "ActivateAbility",
                        description = ability.description,
                        action = ActivateAbility(playerId, entityId, ability.id),
                        additionalCostInfo = costInfo,
                        hasXCost = hasXCost,
                        maxAffordableX = maxAffordableX,
                        manaCostString = abilityManaCost?.toString()
                    )
                )
            }
        }

        return result
    }
}
