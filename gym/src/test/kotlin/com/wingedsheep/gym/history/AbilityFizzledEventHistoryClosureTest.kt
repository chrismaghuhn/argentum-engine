package com.wingedsheep.gym.history

import com.wingedsheep.engine.core.AbilityFizzledEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.contract.PerspectiveEventDisposition
import com.wingedsheep.gym.contract.PerspectiveEventFamily
import com.wingedsheep.gym.contract.PerspectiveEventProjector
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AbilityFizzledEventHistoryClosureTest : FunSpec({
    val self = EntityId("p1")
    val opponent = EntityId("p2")
    val sourceId = EntityId("ability-fizzle-runtime-id")
    val description = "Hidden ability description"
    val reason = "All targets are invalid"

    fun projection(perspectivePlayerId: EntityId) = PerspectiveEventProjector(CardRegistry()).project(
        events = listOf(
            AbilityFizzledEvent(
                sourceId = sourceId,
                description = description,
                reason = reason,
            ),
        ),
        perspectivePlayerId = perspectivePlayerId,
    )

    test("AbilityFizzledEvent projects only its stable reason for both perspectives") {
        val projections = listOf(self, opponent).map(::projection)
        val expectedPayload = buildJsonObject {
            put("type", "ability_fizzled")
            put("reason", reason)
        }

        projections.forEach { result ->
            result.isComplete shouldBe true
            result.classifications.single().rawEventType shouldBe "AbilityFizzledEvent"
            result.classifications.single().disposition shouldBe PerspectiveEventDisposition.EMITTED
            result.batch.entries.single().eventFamily shouldBe PerspectiveEventFamily.ABILITY_FIZZLED
            result.batch.entries.single().semanticPayload shouldBe expectedPayload
            result.batch.canonicalJson() shouldNotContain sourceId.value
            result.batch.canonicalJson() shouldNotContain description
        }

        projections.map { it.batch.entries.single().semanticPayload }.distinct().size shouldBe 1
        projection(self).batch.canonicalJson() shouldBe projections.first().batch.canonicalJson()
    }
})
