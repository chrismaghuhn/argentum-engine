package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ths.cards.ProwlersHelm
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Prowler's Helm (THS #219): equipped creatures can be blocked only by Walls;
 * Equip {2} attaches only to a creature the activating player controls and only
 * at sorcery speed.
 */
class ProwlersHelmScenarioTest : ScenarioTestBase() {

    private val equipAbilityId = ProwlersHelm.activatedAbilities.first().id

    private fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + ProwlersHelm)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    private fun equip(driver: GameTestDriver, player: EntityId, helm: EntityId, creature: EntityId) {
        driver.giveColorlessMana(player, 2)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = helm,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(creature)),
            )
        ).isSuccess shouldBe true
        driver.bothPass()
    }

    init {
        test("equipped creature cannot be blocked by a non-Wall, but a Wall can block it") {
            val game = scenario()
                .withPlayers("Player1", "Player2")
                .withCardOnBattlefield(1, "Grizzly Bears", summoningSickness = false)
                .withCardOnBattlefield(1, "Prowler's Helm")
                .withCardAttachedTo(1, "Prowler's Helm", "Grizzly Bears")
                .withCardOnBattlefield(2, "Hill Giant", summoningSickness = false)
                .withCardOnBattlefield(2, "Wall of Mulch", summoningSickness = false)
                .withActivePlayer(1)
                .inPhase(Phase.COMBAT, Step.BEGIN_COMBAT)
                .build()

            game.advanceToPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
            game.declareAttackers(mapOf("Grizzly Bears" to 2)).error shouldBe null
            game.advanceToPhase(Phase.COMBAT, Step.DECLARE_BLOCKERS)

            withClue("a non-Wall blocker is illegal for the equipped creature") {
                game.declareBlockers(mapOf("Hill Giant" to listOf("Grizzly Bears"))).error shouldNotBe null
            }
            withClue("a Wall remains a legal blocker") {
                game.declareBlockers(mapOf("Wall of Mulch" to listOf("Grizzly Bears"))).error shouldBe null
            }
        }

        test("without an attachment, the creature has no Wall-only blocking restriction") {
            val game = scenario()
                .withPlayers("Player1", "Player2")
                .withCardOnBattlefield(1, "Grizzly Bears", summoningSickness = false)
                .withCardOnBattlefield(1, "Prowler's Helm")
                .withCardOnBattlefield(2, "Hill Giant", summoningSickness = false)
                .withActivePlayer(1)
                .inPhase(Phase.COMBAT, Step.BEGIN_COMBAT)
                .build()

            game.advanceToPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
            game.declareAttackers(mapOf("Grizzly Bears" to 2)).error shouldBe null
            game.advanceToPhase(Phase.COMBAT, Step.DECLARE_BLOCKERS)
            game.declareBlockers(mapOf("Hill Giant" to listOf("Grizzly Bears"))).error shouldBe null
        }

        test("Equip targets only your creatures and is sorcery speed") {
            val driver = createDriver()
            val player = driver.activePlayer!!
            val opponent = driver.getOpponent(player)
            val ownCreature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
            val opponentCreature = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
            val helm = driver.putPermanentOnBattlefield(player, "Prowler's Helm")

            driver.giveColorlessMana(player, 2)
            driver.submit(
                ActivateAbility(
                    playerId = player,
                    sourceId = helm,
                    abilityId = equipAbilityId,
                    targets = listOf(ChosenTarget.Permanent(opponentCreature)),
                )
            ).isSuccess shouldBe false
            driver.state.getEntity(helm)?.get<AttachedToComponent>() shouldBe null

            equip(driver, player, helm, ownCreature)
            driver.state.getEntity(helm)?.get<AttachedToComponent>()?.targetId shouldBe ownCreature

            driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
            driver.giveColorlessMana(player, 2)
            driver.submit(
                ActivateAbility(
                    playerId = player,
                    sourceId = helm,
                    abilityId = equipAbilityId,
                    targets = listOf(ChosenTarget.Permanent(ownCreature)),
                )
            ).isSuccess shouldBe false
            driver.state.getEntity(helm)?.get<AttachedToComponent>()?.targetId shouldBe ownCreature
        }
    }
}
