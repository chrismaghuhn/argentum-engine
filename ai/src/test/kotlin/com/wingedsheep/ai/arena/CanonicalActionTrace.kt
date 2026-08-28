package com.wingedsheep.ai.arena

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import com.wingedsheep.sdk.scripting.AlternativePaymentChoice

/**
 * Stable semantic projection used by deterministic arena traces.
 *
 * This is deliberately separate from Kotlin's generated [Any.toString] for [GameAction]. The
 * action classes carry internal continuation fields so a paused engine can resume safely; those
 * fields are execution metadata, not a new AI choice and must not move the historical baseline.
 *
 * The field lists below are explicit so adding another internal action field cannot silently become
 * part of the trace contract. A new player-visible choice must be added here deliberately.
 */
internal fun canonicalActionTrace(action: GameAction): String = when (action) {
    is PassPriority -> fields("PassPriority", "playerId=${action.playerId}")
    is CastSpell -> fields(
        "CastSpell",
        "playerId=${action.playerId}",
        "cardId=${action.cardId}",
        "targets=${canonicalTargets(action.targets)}",
        "xValue=${action.xValue}",
        "paymentStrategy=${canonicalPaymentStrategy(action.paymentStrategy)}",
        "alternativePayment=${canonicalAlternativePayment(action.alternativePayment)}",
        "additionalCostPayment=${canonicalAdditionalCostPayment(action.additionalCostPayment)}",
        "castFaceDown=${action.castFaceDown}",
        "declaredCostSlot=${action.declaredCostSlot}",
        "wasWaterbendPaid=${action.wasWaterbendPaid}",
        "giftRecipient=${action.giftRecipient}",
        "splicedCardIds=${action.splicedCardIds}",
        "damageDistribution=${canonicalEntityIntMap(action.damageDistribution)}",
        "useAlternativeCost=${action.useAlternativeCost}",
        "chosenModes=${action.chosenModes}",
        "modeTargetsOrdered=${action.modeTargetsOrdered.map(::canonicalTargets)}",
        "modeDamageDistribution=${canonicalModeDamageDistribution(action.modeDamageDistribution)}",
        "graveyardLifeCost=${action.graveyardLifeCost}",
        "graveyardCastRider=${canonicalGraveyardCastRider(action.graveyardCastRider)}",
        "conspiredCreatures=${action.conspiredCreatures}",
        "casualtyCreature=${action.casualtyCreature}",
        "faceIndex=${action.faceIndex}",
        "useWithoutPayingManaCost=${action.useWithoutPayingManaCost}",
        "alternativeCostType=${action.alternativeCostType}",
    )
    is ActivateAbility -> fields(
        "ActivateAbility",
        "playerId=${action.playerId}",
        "sourceId=${action.sourceId}",
        "abilityId=${action.abilityId}",
        "targets=${canonicalTargets(action.targets)}",
        "costPayment=${canonicalAdditionalCostPayment(action.costPayment)}",
        "manaColorChoice=${action.manaColorChoice}",
        "xValue=${action.xValue}",
        "repeatCount=${action.repeatCount}",
        "paymentStrategy=${canonicalPaymentStrategy(action.paymentStrategy)}",
        "alternativePayment=${canonicalAlternativePayment(action.alternativePayment)}",
        "damageDistribution=${canonicalEntityIntMap(action.damageDistribution)}",
    )
    is CycleCard -> fields(
        "CycleCard",
        "playerId=${action.playerId}",
        "cardId=${action.cardId}",
        "paymentStrategy=${canonicalPaymentStrategy(action.paymentStrategy)}",
        "xValue=${action.xValue}",
    )
    is PlotCard -> fields(
        "PlotCard",
        "playerId=${action.playerId}",
        "cardId=${action.cardId}",
        "paymentStrategy=${canonicalPaymentStrategy(action.paymentStrategy)}",
    )
    is ForetellCard -> fields(
        "ForetellCard",
        "playerId=${action.playerId}",
        "cardId=${action.cardId}",
        "paymentStrategy=${canonicalPaymentStrategy(action.paymentStrategy)}",
    )
    is SuspendCardFromHand -> fields(
        "SuspendCardFromHand",
        "playerId=${action.playerId}",
        "cardId=${action.cardId}",
        "paymentStrategy=${canonicalPaymentStrategy(action.paymentStrategy)}",
    )
    is TypecycleCard -> fields(
        "TypecycleCard",
        "playerId=${action.playerId}",
        "cardId=${action.cardId}",
        "paymentStrategy=${canonicalPaymentStrategy(action.paymentStrategy)}",
    )
    is PlayLand -> fields(
        "PlayLand",
        "playerId=${action.playerId}",
        "cardId=${action.cardId}",
    )
    is DeclareAttackers -> fields(
        "DeclareAttackers",
        "playerId=${action.playerId}",
        "attackers=${action.attackers}",
        "bands=${action.bands}",
    )
    is DeclareBlockers -> fields(
        "DeclareBlockers",
        "playerId=${action.playerId}",
        "blockers=${action.blockers}",
    )
    is OrderBlockers -> fields(
        "OrderBlockers",
        "playerId=${action.playerId}",
        "attackerId=${action.attackerId}",
        "orderedBlockers=${action.orderedBlockers}",
    )
    is ChooseManaColor -> fields(
        "ChooseManaColor",
        "playerId=${action.playerId}",
        "color=${action.color}",
    )
    is SubmitDecision -> fields(
        "SubmitDecision",
        "playerId=${action.playerId}",
        "response=${canonicalDecisionResponse(action.response)}",
    )
    is TakeMulligan -> fields("TakeMulligan", "playerId=${action.playerId}")
    is KeepHand -> fields("KeepHand", "playerId=${action.playerId}")
    is BottomCards -> fields(
        "BottomCards",
        "playerId=${action.playerId}",
        "cardIds=${action.cardIds}",
    )
    is Concede -> fields("Concede", "playerId=${action.playerId}")
    is CrewVehicle -> fields(
        "CrewVehicle",
        "playerId=${action.playerId}",
        "vehicleId=${action.vehicleId}",
        "crewCreatures=${action.crewCreatures}",
        "crewAbilityKey=${action.crewAbilityKey}",
    )
    is SaddleMount -> fields(
        "SaddleMount",
        "playerId=${action.playerId}",
        "mountId=${action.mountId}",
        "saddleCreatures=${action.saddleCreatures}",
    )
    is TurnFaceUp -> fields(
        "TurnFaceUp",
        "playerId=${action.playerId}",
        "sourceId=${action.sourceId}",
        "paymentStrategy=${canonicalPaymentStrategy(action.paymentStrategy)}",
        "costTargetIds=${action.costTargetIds}",
        "xValue=${action.xValue}",
        "procedureIndex=${action.procedureIndex}",
    )
    is UnlockRoomDoor -> fields(
        "UnlockRoomDoor",
        "playerId=${action.playerId}",
        "roomId=${action.roomId}",
        "faceId=${action.faceId}",
        "paymentStrategy=${canonicalPaymentStrategy(action.paymentStrategy)}",
    )
}

private fun fields(type: String, vararg fields: String): String =
    "$type(${fields.joinToString(", ")})"

private fun canonicalEntityIntMap(map: Map<EntityId, Int>?): String =
    map?.entries
        ?.sortedBy { it.key.value }
        ?.joinToString(prefix = "{", postfix = "}") { "${it.key.value}=${it.value}" }
        ?: "null"

private fun canonicalModeDamageDistribution(map: Map<Int, Map<EntityId, Int>>): String =
    map.entries
        .sortedBy { it.key }
        .joinToString(prefix = "{", postfix = "}") {
            "${it.key}=${canonicalEntityIntMap(it.value)}"
        }

private fun canonicalTargets(targets: List<ChosenTarget>): String =
    targets.joinToString(prefix = "[", postfix = "]") { target ->
        when (target) {
            is ChosenTarget.Player -> fields("Player", "playerId=${target.playerId}")
            is ChosenTarget.Permanent -> fields("Permanent", "entityId=${target.entityId}")
            is ChosenTarget.Card -> fields(
                "Card",
                "cardId=${target.cardId}",
                "ownerId=${target.ownerId}",
                "zone=${target.zone}",
            )
            is ChosenTarget.Spell -> fields("Spell", "spellEntityId=${target.spellEntityId}")
        }
    }

private fun canonicalPaymentStrategy(strategy: PaymentStrategy): String = when (strategy) {
    PaymentStrategy.AutoPay -> "AutoPay"
    PaymentStrategy.FromPool -> "FromPool"
    is PaymentStrategy.Explicit -> fields(
        "Explicit",
        "manaAbilitiesToActivate=${strategy.manaAbilitiesToActivate}",
    )
    is PaymentStrategy.ExplicitV2 -> fields(
        "ExplicitV2",
        "manaAbilitiesToActivate=${strategy.manaAbilitiesToActivate}",
    )
    is PaymentStrategy.ExplicitV3 -> fields(
        "ExplicitV3",
        "paymentPlan=${strategy.paymentPlan}",
    )
}

private fun canonicalAlternativePayment(payment: AlternativePaymentChoice?): String =
    payment?.let {
        fields(
            "AlternativePaymentChoice",
            "delvedCards=${it.delvedCards}",
            "convokedCreatures=${it.convokedCreatures}",
            "harmonizeCreature=${it.harmonizeCreature}",
            "tapForGenericPermanents=${it.tapForGenericPermanents}",
            *listOfNotNull(it.equipPayment?.let { mode -> "equipPayment=$mode" }).toTypedArray(),
        )
    } ?: "null"

private fun canonicalAdditionalCostPayment(payment: AdditionalCostPayment?): String =
    payment?.let {
        fields(
            "AdditionalCostPayment",
            "sacrificedPermanents=${it.sacrificedPermanents}",
            "discardedCards=${it.discardedCards}",
            "lifePaid=${it.lifePaid}",
            "exiledCards=${it.exiledCards}",
            "variableCostPermanents=${it.variableCostPermanents}",
            "beheldCards=${it.beheldCards}",
            "tappedPermanents=${it.tappedPermanents}",
            "bouncedPermanents=${it.bouncedPermanents}",
            "blightTargets=${it.blightTargets}",
            "blightAmount=${it.blightAmount}",
            "payXLifeAmount=${it.payXLifeAmount}",
            "distributedCounterRemovals=${it.distributedCounterRemovals}",
        )
    } ?: "null"

private fun canonicalGraveyardCastRider(rider: GraveyardCastRiderSelection?): String =
    rider?.let {
        fields(
            "GraveyardCastRiderSelection",
            "entersWithCounter=${it.entersWithCounter}",
            "addedSubtype=${it.addedSubtype}",
            "exileInsteadOfGraveyard=${it.exileInsteadOfGraveyard}",
        )
    } ?: "null"

/**
 * Decision ids are routing nonces, not player choices. Keep the response payload and omit only
 * that id so replay-generated ids cannot move a semantic action trace.
 */
internal fun canonicalDecisionResponse(response: DecisionResponse): String = when (response) {
    is TargetsResponse -> fields("TargetsResponse", "selectedTargets=${response.selectedTargets}")
    is CardsSelectedResponse -> fields("CardsSelectedResponse", "selectedCards=${response.selectedCards}")
    is YesNoResponse -> fields("YesNoResponse", "choice=${response.choice}")
    is BatchYesNoResponse -> fields(
        "BatchYesNoResponse",
        "choice=${response.choice}",
        "applyToAll=${response.applyToAll}",
    )
    is ModesChosenResponse -> fields("ModesChosenResponse", "selectedModes=${response.selectedModes}")
    is ColorChosenResponse -> fields("ColorChosenResponse", "color=${response.color}")
    is NumberChosenResponse -> fields("NumberChosenResponse", "number=${response.number}")
    is DistributionResponse -> fields(
        "DistributionResponse",
        "distribution=${canonicalEntityIntMap(response.distribution)}",
    )
    is OrderedResponse -> fields("OrderedResponse", "orderedObjects=${response.orderedObjects}")
    is PilesSplitResponse -> fields("PilesSplitResponse", "piles=${response.piles}")
    is OptionChosenResponse -> fields("OptionChosenResponse", "optionIndex=${response.optionIndex}")
    is ReplacementChosenResponse -> fields(
        "ReplacementChosenResponse",
        "fromIndex=${response.fromIndex}",
        "toIndex=${response.toIndex}",
    )
    is BudgetModalResponse -> fields(
        "BudgetModalResponse",
        "selectedModeIndices=${response.selectedModeIndices}",
    )
    is DamageAssignmentResponse -> fields(
        "DamageAssignmentResponse",
        "assignments=${canonicalEntityIntMap(response.assignments)}",
    )
    is ManaSourcesSelectedResponse -> fields(
        "ManaSourcesSelectedResponse",
        "selectedSources=${response.selectedSources}",
        "autoPay=${response.autoPay}",
        "waterbendPermanents=${response.waterbendPermanents}",
        "declined=${response.declined}",
    )
    is CombatResolutionResponse -> fields(
        "CombatResolutionResponse",
        "edges=${response.edges}",
        "orderedBlockers=${response.orderedBlockers}",
        "orderedAttackers=${response.orderedAttackers}",
    )
    is CancelDecisionResponse -> "CancelDecisionResponse()"
}
