package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.AttachmentsComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Balan, Wandering Knight (Commander 2017 #2).
 *
 * Current Oracle:
 *   First strike
 *   Balan has double strike as long as two or more Equipment are attached to it.
 *   {1}{W}: Attach all Equipment you control to Balan.
 *
 * The activated ability has no target and no player choice: every Equipment controlled by the
 * activating player must move to Balan when the ability resolves. The tests deliberately assert
 * that resolution does not open a hidden selection decision.
 */
class BalanWanderingKnightScenarioTest : ScenarioTestBase() {

    private companion object {
        const val BALAN = "Balan, Wandering Knight"
        const val HOST = "Grizzly Bears"
        const val EQUIPMENT = "Bonesplitter"
        const val SECOND_EQUIPMENT = "Fireshrieker"
        const val OPPONENT_EQUIPMENT = "Loxodon Warhammer"
    }

    private fun balanAbilityId() =
        cardRegistry.getCard(BALAN)!!.script.activatedAbilities.single().id

    private fun thresholdScenario(attached: List<String>, aura: Boolean = false): TestGame {
        var builder = scenario()
            .withPlayers()
            .withCardOnBattlefield(1, BALAN)

        attached.forEach { equipment ->
            builder = builder
                .withCardAttachedTo(1, equipment, BALAN)
        }

        if (aura) {
            builder = builder
                .withCardOnBattlefield(1, "Pacifism")
                .withCardAttachedTo(1, "Pacifism", BALAN)
        }

        return builder
            .withActivePlayer(1)
            .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
            .build()
    }

    private fun activateBalan(game: TestGame) =
        game.execute(ActivateAbility(game.player1Id, game.findPermanent(BALAN)!!, balanAbilityId()))

    init {
        test("always has first strike and gains double strike only at two attached Equipment") {
            val noEquipment = thresholdScenario(emptyList())
            val oneEquipment = thresholdScenario(listOf(EQUIPMENT))
            val twoEquipment = thresholdScenario(listOf(EQUIPMENT, SECOND_EQUIPMENT))

            withClue("Balan has first strike without Equipment") {
                noEquipment.state.projectedState.hasKeyword(
                    noEquipment.findPermanent(BALAN)!!,
                    Keyword.FIRST_STRIKE
                ) shouldBe true
            }
            withClue("one attached Equipment is below the double-strike threshold") {
                oneEquipment.state.projectedState.hasKeyword(
                    oneEquipment.findPermanent(BALAN)!!,
                    Keyword.DOUBLE_STRIKE
                ) shouldBe false
            }
            withClue("two attached Equipment grant double strike") {
                twoEquipment.state.projectedState.hasKeyword(
                    twoEquipment.findPermanent(BALAN)!!,
                    Keyword.DOUBLE_STRIKE
                ) shouldBe true
            }
        }

        test("counts Equipment, not an attached Aura, for the double-strike threshold") {
            val game = thresholdScenario(listOf(EQUIPMENT), aura = true)

            withClue("one Equipment plus one Aura does not satisfy two Equipment") {
                game.state.projectedState.hasKeyword(
                    game.findPermanent(BALAN)!!,
                    Keyword.DOUBLE_STRIKE
                ) shouldBe false
            }
        }

        test("attaches every Equipment I control without a selection decision") {
            val builder = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, BALAN)
                .withCardOnBattlefield(1, HOST)
                .withCardAttachedTo(1, EQUIPMENT, HOST)
                .withCardOnBattlefield(1, SECOND_EQUIPMENT)
                .withCardOnBattlefield(2, "Hill Giant")
                .withCardAttachedTo(2, OPPONENT_EQUIPMENT, "Hill Giant")
                .withLandsOnBattlefield(1, "Plains", 2)
                .withActivePlayer(1)
                .inPhase(Phase.POSTCOMBAT_MAIN, Step.POSTCOMBAT_MAIN)

            val game = builder.build()
            val balanId = game.findPermanent(BALAN)!!
            val firstEquipmentId = game.findPermanent(EQUIPMENT)!!
            val secondEquipmentId = game.findPermanent(SECOND_EQUIPMENT)!!
            val opponentEquipmentId = game.findPermanent(OPPONENT_EQUIPMENT)!!
            val opponentHostId = game.findPermanent("Hill Giant")!!

            activateBalan(game).error shouldBe null
            game.resolveStack()

            withClue("the all-Equipment ability does not create an implicit choice") {
                game.getPendingDecision() shouldBe null
            }
            withClue("the attached Equipment moves from its old host to Balan") {
                game.state.getEntity(firstEquipmentId)?.get<AttachedToComponent>()?.targetId shouldBe balanId
            }
            withClue("the unattached Equipment is also attached to Balan") {
                game.state.getEntity(secondEquipmentId)?.get<AttachedToComponent>()?.targetId shouldBe balanId
            }
            withClue("the controller's attachment list contains both Equipment") {
                game.state.getEntity(balanId)?.get<AttachmentsComponent>()?.attachedIds.orEmpty() shouldContain firstEquipmentId
                game.state.getEntity(balanId)?.get<AttachmentsComponent>()?.attachedIds.orEmpty() shouldContain secondEquipmentId
            }
            withClue("an opponent-controlled Equipment is not moved") {
                game.state.getEntity(opponentEquipmentId)?.get<AttachedToComponent>()?.targetId shouldBe opponentHostId
                game.state.getEntity(opponentHostId)?.get<AttachmentsComponent>()?.attachedIds.orEmpty() shouldContain opponentEquipmentId
                game.state.getEntity(opponentHostId)?.get<AttachmentsComponent>()?.attachedIds.orEmpty() shouldNotContain firstEquipmentId
            }
        }
    }
}
