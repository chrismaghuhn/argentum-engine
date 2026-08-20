package com.wingedsheep.engine.handlers.effects.library

import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.PipelineState
import com.wingedsheep.engine.handlers.TargetFinder
import com.wingedsheep.engine.handlers.effects.permanent.attachments.AttachmentLegality
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.CollectionFilter
import com.wingedsheep.sdk.scripting.effects.FilterCollectionEffect
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class AttachableToFilterExecutorTest : FunSpec({

    test("an unresolved destination produces an empty matching domain and preserves non-matches") {
        val playerId = EntityId.generate()
        val attachmentId = EntityId.generate()
        val attachment = CardComponent(
            cardDefinitionId = "Test Equipment",
            name = "Test Equipment",
            manaCost = ManaCost(emptyList()),
            typeLine = TypeLine(cardTypes = setOf(CardType.ARTIFACT)),
            ownerId = playerId,
        )
        val state = GameState()
            .withEntity(playerId, ComponentContainer())
            .withEntity(
                attachmentId,
                ComponentContainer()
                    .with(attachment)
                    .with(OwnerComponent(playerId))
                    .with(ControllerComponent(playerId))
            )
            .addToZone(ZoneKey(playerId, Zone.BATTLEFIELD), attachmentId)
        val context = EffectContext(
            sourceId = null,
            controllerId = playerId,
            pipeline = PipelineState(storedCollections = mapOf("input" to listOf(attachmentId))),
        )
        val effect = FilterCollectionEffect(
            from = "input",
            filter = CollectionFilter.AttachableTo(EffectTarget.ContextTarget(0)),
            storeMatching = "matching",
            storeNonMatching = "nonMatching",
        )
        val executor = FilterCollectionExecutor(AttachmentLegality(CardRegistry(), TargetFinder()))

        val result = executor.execute(state, effect, context)

        result.isSuccess shouldBe true
        result.updatedCollections["matching"] shouldContainExactly emptyList()
        result.updatedCollections["nonMatching"] shouldContainExactly listOf(attachmentId)
    }
})
