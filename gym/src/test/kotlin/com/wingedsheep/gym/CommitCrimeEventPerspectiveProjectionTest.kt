package com.wingedsheep.gym

import com.wingedsheep.engine.core.CommitCrimeEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.contract.PerspectiveEventDisposition
import com.wingedsheep.gym.contract.PerspectiveEventProjector
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class CommitCrimeEventPerspectiveProjectionTest : FunSpec({
    val self = EntityId.of("p1")
    val other = EntityId.of("p2")
    val source = EntityId.of("private-source-runtime-id")
    val sourceName = "Private source name"

    test("CommitCrimeEvent projects a minimal perspective-safe family for both perspectives") {
        listOf(self to "SELF", other to "OTHER").forEach { (perspectivePlayerId, expectedRole) ->
            val projector = PerspectiveEventProjector(CardRegistry())
            val event = CommitCrimeEvent(
                playerId = self,
                sourceEntityId = source,
                sourceName = sourceName,
            )
            val projection = projector.project(
                events = listOf(event),
                perspectivePlayerId = perspectivePlayerId,
            )
            val repeatedProjection = projector.project(
                events = listOf(event),
                perspectivePlayerId = perspectivePlayerId,
            )

            projection.isComplete shouldBe true
            projection.classifications.single().disposition shouldBe PerspectiveEventDisposition.EMITTED
            projection.batch.entries.single().eventFamily.name shouldBe "COMMIT_CRIME"
            projection.batch.entries.single().semanticPayload.toString() shouldBe
                "{\"type\":\"commit_crime\",\"playerRole\":\"$expectedRole\"}"
            projection.batch.canonicalJson() shouldNotContain source.value
            projection.batch.canonicalJson() shouldNotContain sourceName
            projection.batch.canonicalJson() shouldBe repeatedProjection.batch.canonicalJson()
        }
    }
})
