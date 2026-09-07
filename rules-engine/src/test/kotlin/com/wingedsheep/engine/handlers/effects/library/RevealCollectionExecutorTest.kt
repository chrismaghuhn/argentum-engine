package com.wingedsheep.engine.handlers.effects.library

import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.PipelineState
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.RevealCollectionEffect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * RED characterization for the generic reveal-only persistence gap.
 *
 * A public reveal is an authoritative information mutation even when the reveal effect does not
 * move a card. The Rules state must retain the audience through the same visibility metadata used
 * by later perspective projections.
 */
class RevealCollectionExecutorTest : FunSpec({
    val owner = EntityId.of("owner")
    val opponent = EntityId.of("opponent")
    val cardId = EntityId.of("revealed-card")

    fun state(): GameState = GameState(
        turnOrder = listOf(owner, opponent),
    )
        .withEntity(owner, ComponentContainer.EMPTY)
        .withEntity(opponent, ComponentContainer.EMPTY)
        .withEntity(
            cardId,
            ComponentContainer.of(
                CardComponent(
                    cardDefinitionId = "revealed-card",
                    name = "Revealed Card",
                    manaCost = ManaCost.ZERO,
                    typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
                    ownerId = owner,
                ),
                OwnerComponent(owner),
            ),
        )
        .addToZone(ZoneKey(owner, Zone.LIBRARY), cardId)

    test("reveal-only collection persists public audience metadata") {
        val result = RevealCollectionExecutor().execute(
            state = state(),
            effect = RevealCollectionEffect(from = "revealed"),
            context = EffectContext(
                sourceId = null,
                controllerId = owner,
                pipeline = PipelineState(
                    storedCollections = mapOf("revealed" to listOf(cardId)),
                ),
            ),
        )

        result.state.getEntity(cardId)
            ?.get<RevealedToComponent>()
            ?.playerIds shouldBe setOf(owner, opponent)
    }
})
