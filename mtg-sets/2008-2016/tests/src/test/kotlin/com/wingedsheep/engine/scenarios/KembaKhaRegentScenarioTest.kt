package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/**
 * Kemba, Kha Regent (SOM #12).
 *
 * At the beginning of your upkeep, Kemba creates one 2/2 white Cat for each Equipment
 * attached to Kemba. The count is taken from Kemba's attachments, not from every Equipment
 * controlled by its controller or from Equipment attached to another creature.
 */
class KembaKhaRegentScenarioTest : ScenarioTestBase() {

    private fun upkeepWithKemba(
        attachedEquipment: List<String> = emptyList(),
        otherCreatureEquipment: String? = null
    ) = run {
        var builder = scenario()
            .withPlayers("P1", "P2")
            .withCardOnBattlefield(1, "Kemba, Kha Regent")

        attachedEquipment.forEach { equipment ->
            builder = builder
                .withCardOnBattlefield(1, equipment)
                .withCardAttachedTo(1, equipment, "Kemba, Kha Regent")
        }

        if (otherCreatureEquipment != null) {
            builder = builder
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardOnBattlefield(1, otherCreatureEquipment)
                .withCardAttachedTo(1, otherCreatureEquipment, "Grizzly Bears")
        }

        builder
            .withActivePlayer(2)
            .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
            .build()
            .also {
                it.passUntilPhase(Phase.BEGINNING, Step.UPKEEP)
                it.resolveStack()
            }
    }

    init {
        test("no Equipment attached to Kemba creates no Cats") {
            val game = upkeepWithKemba()

            game.findAllPermanents("Cat Token").size shouldBe 0
        }

        test("one Equipment attached to Kemba creates one 2/2 white Cat") {
            val game = upkeepWithKemba(attachedEquipment = listOf("Bonesplitter"))

            val cats = game.findAllPermanents("Cat Token")
            cats.size shouldBe 1
            val cat = cats.single()
            withClue("the token has the printed 2/2 characteristics") {
                game.state.projectedState.getPower(cat) shouldBe 2
                game.state.projectedState.getToughness(cat) shouldBe 2
                game.state.projectedState.hasColor(cat, Color.WHITE) shouldBe true
                game.state.projectedState.getSubtypes(cat) shouldBe setOf("Cat")
            }
        }

        test("two attached Equipment create two Cats, while another creature's Equipment does not count") {
            val game = upkeepWithKemba(
                attachedEquipment = listOf("Bonesplitter", "Fireshrieker"),
                otherCreatureEquipment = "Lightning Greaves"
            )

            game.findAllPermanents("Cat Token").size shouldBe 2
        }
    }
}
