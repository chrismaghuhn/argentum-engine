package com.wingedsheep.engine.mechanics.combat

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.engine.state.components.battlefield.BattlefieldEntryTimestampComponent
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CombatObjectOrderTest : FunSpec({
    test("orders by state-owned rank independently of input collection order") {
        val (state, objects) = twoObjects()
        val rankedState = state.withRanks(objects, first = 20L, second = 10L)

        CombatObjectOrder.order(rankedState, objects) shouldBe listOf(objects[1], objects[0])
        CombatObjectOrder.order(rankedState, objects.reversed()) shouldBe listOf(objects[1], objects[0])
    }

    test("uses the battlefield entry timestamp compatibility fallback") {
        val (state, objects) = twoObjects()
        val withoutObjectStamps = state.copy(
            objectIdentityStamps = state.objectIdentityStamps - objects.toSet(),
        )
        val expected = objects.sortedBy { entityId ->
            withoutObjectStamps.getEntity(entityId)
                ?.get<BattlefieldEntryTimestampComponent>()
                ?.timestamp
                ?: error("missing battlefield-entry timestamp for $entityId")
        }

        CombatObjectOrder.order(withoutObjectStamps, objects) shouldBe expected
    }

    test("rejects a missing rank") {
        CombatObjectOrder.order(GameState(), listOf(EntityId("missing-object"))) shouldBe null
    }

    test("rejects duplicate object ranks") {
        val (state, objects) = twoObjects()
        val duplicateRankState = state.withRanks(objects, first = 10L, second = 10L)

        CombatObjectOrder.order(duplicateRankState, objects) shouldBe null
    }

    test("rejects duplicate requested objects") {
        val (state, objects) = twoObjects()

        CombatObjectOrder.order(state, listOf(objects[0], objects[0])) shouldBe null
    }
})

private fun twoObjects(): Pair<GameState, List<EntityId>> {
    val driver = GameTestDriver()
    driver.registerCards(TestCards.all)
    driver.initMirrorMatch(
        deck = Deck.of("Forest" to 20, "Grizzly Bears" to 20),
        skipMulligans = true,
    )
    val first = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
    val second = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
    return driver.state to listOf(first, second)
}

private fun GameState.withRanks(
    objects: List<EntityId>,
    first: Long,
    second: Long,
): GameState = copy(
    objectIdentityStamps = objectIdentityStamps +
        (objects[0] to first) +
        (objects[1] to second),
)
