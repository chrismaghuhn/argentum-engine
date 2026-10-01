package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.mrd.cards.MaskOfMemory
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import com.wingedsheep.engine.core.Outcome
import io.kotest.matchers.shouldNotBe

/**
 * Mask of Memory (MRD) — "Whenever equipped creature deals combat damage to a player, you may draw
 * two cards. If you do, discard a card." Equip {1}.
 *
 * The "if you do" is not a second decision: the discard is the price of the draw, so both sit inside
 * one may. These tests pin that accepting costs exactly one card from hand and that declining costs
 * nothing — the failure mode being a discard that fires even when the draw was declined.
 */
class MaskOfMemoryScenarioTest : ScenarioTestBase() {

    private val equipAbilityId = MaskOfMemory.activatedAbilities.first().id

    private fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + MaskOfMemory)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    private fun equip(driver: GameTestDriver, player: EntityId, mask: EntityId, creature: EntityId) {
        driver.giveColorlessMana(player, 1)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = mask,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(creature)),
            )
        ).outcome shouldBe Outcome.Done
        driver.bothPass()
    }

    init {
        context("Mask of Memory — draw two, discard one, on combat damage") {
            test("accepting draws two and then discards one") {
                val game = scenario()
                    .withPlayers("Player1", "Player2")
                    .withCardOnBattlefield(1, "Grizzly Bears", summoningSickness = false)
                    .withCardAttachedTo(1, "Mask of Memory", "Grizzly Bears")
                    .withCardsInHand(1, "Mountain", 1)
                    .withCardInLibrary(1, "Forest")
                    .withCardInLibrary(1, "Island")
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                val libBefore = game.state.getLibrary(game.player1Id).size
                val handBefore = game.state.getHand(game.player1Id).size
                val gyBefore = game.state.getGraveyard(game.player1Id).size

                game.passUntilPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
                game.declareAttackers(mapOf("Grizzly Bears" to 2)).error shouldBe null
                game.passUntilPhase(Phase.COMBAT, Step.DECLARE_BLOCKERS)
                game.declareNoBlockers()
                game.passUntilPhase(Phase.COMBAT, Step.COMBAT_DAMAGE)
                game.passPriority()
                game.resolveStack()

                game.answerYesNo(true).error shouldBe null

                val discard = game.getPendingDecision()
                withClue("the accepted branch always reaches the discard") {
                    (discard is SelectCardsDecision) shouldBe true
                }
                game.selectCards(listOf((discard as SelectCardsDecision).options.first()))
                    .error shouldBe null
                game.resolveStack()

                withClue("two drawn off the top") {
                    game.state.getLibrary(game.player1Id).size shouldBe libBefore - 2
                }
                withClue("net +1 in hand: drew two, discarded one") {
                    game.state.getHand(game.player1Id).size shouldBe handBefore + 1
                }
                withClue("the discarded card is in the graveyard") {
                    game.state.getGraveyard(game.player1Id).size shouldBe gyBefore + 1
                }
            }

            test("declining draws nothing and discards nothing") {
                val game = scenario()
                    .withPlayers("Player1", "Player2")
                    .withCardOnBattlefield(1, "Grizzly Bears", summoningSickness = false)
                    .withCardAttachedTo(1, "Mask of Memory", "Grizzly Bears")
                    .withCardsInHand(1, "Mountain", 1)
                    .withCardInLibrary(1, "Forest")
                    .withCardInLibrary(1, "Island")
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                val libBefore = game.state.getLibrary(game.player1Id).size
                val handBefore = game.state.getHand(game.player1Id).size
                val gyBefore = game.state.getGraveyard(game.player1Id).size

                game.passUntilPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
                game.declareAttackers(mapOf("Grizzly Bears" to 2)).error shouldBe null
                game.passUntilPhase(Phase.COMBAT, Step.DECLARE_BLOCKERS)
                game.declareNoBlockers()
                game.passUntilPhase(Phase.COMBAT, Step.COMBAT_DAMAGE)
                game.passPriority()
                game.resolveStack()

                game.answerYesNo(false).error shouldBe null
                game.resolveStack()

                withClue("declining the draw also skips the 'if you do' discard") {
                    game.state.getLibrary(game.player1Id).size shouldBe libBefore
                    game.state.getHand(game.player1Id).size shouldBe handBefore
                    game.state.getGraveyard(game.player1Id).size shouldBe gyBefore
                }
            }
        }

        test("Equip only targets your creatures and only works at sorcery timing") {
            val driver = createDriver()
            val player = driver.activePlayer!!
            val opponent = driver.getOpponent(player)
            val ownCreature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
            val secondCreature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
            val opponentCreature = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
            val mask = driver.putPermanentOnBattlefield(player, "Mask of Memory")

            driver.giveColorlessMana(player, 1)
            driver.submit(
                ActivateAbility(
                    playerId = player,
                    sourceId = mask,
                    abilityId = equipAbilityId,
                    targets = listOf(ChosenTarget.Permanent(opponentCreature)),
                )
            ).outcome shouldNotBe Outcome.Done
            driver.state.getEntity(mask)?.get<AttachedToComponent>() shouldBe null

            equip(driver, player, mask, ownCreature)
            driver.state.getEntity(mask)?.get<AttachedToComponent>()?.targetId shouldBe ownCreature

            driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
            driver.giveColorlessMana(player, 1)
            driver.submit(
                ActivateAbility(
                    playerId = player,
                    sourceId = mask,
                    abilityId = equipAbilityId,
                    targets = listOf(ChosenTarget.Permanent(secondCreature)),
                )
            ).outcome shouldNotBe Outcome.Done
            driver.state.getEntity(mask)?.get<AttachedToComponent>()?.targetId shouldBe ownCreature
        }

        test("an equipped creature that dies to the same combat damage still triggers the Mask") {
            // Trample Beast (5/5 trample) is blocked by a 1/1 deathtouch Rat: it assigns lethal 1 to
            // the Rat and tramples 4 over, while the Rat's deathtouch damage kills it in the same
            // step. The Mask's ability triggered when that damage was dealt (CR 603.2), while the
            // Beast was still equipped; the state-based actions that destroy the Beast and unattach
            // the Mask (CR 704.5n) only happen afterwards.
            val driver = createDriver()
            val player = driver.activePlayer!!
            val opponent = driver.getOpponent(player)
            val beast = driver.putCreatureOnBattlefield(player, "Trample Beast")
            driver.removeSummoningSickness(beast)
            val rat = driver.putCreatureOnBattlefield(opponent, "Deathtouch Rat")
            val mask = driver.putPermanentOnBattlefield(player, "Mask of Memory")
            equip(driver, player, mask, beast)
            val libraryBefore = driver.state.getLibrary(player).size

            driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
            driver.declareAttackers(player, listOf(beast), opponent)
            driver.bothPass()
            driver.declareBlockers(opponent, mapOf(rat to listOf(beast)))
            var maskOffered = false
            var guard = 0
            while (driver.state.step != Step.END_COMBAT && guard++ < 50) {
                val decision = driver.state.pendingDecision
                when {
                    decision is YesNoDecision -> {
                        maskOffered = true
                        driver.submitYesNo(decision.playerId, true)
                    }
                    decision != null -> driver.autoResolveDecision()
                    else -> driver.passPriority(driver.state.priorityPlayerId ?: break)
                }
            }

            withClue("the Beast died to the Rat's deathtouch damage in the same step") {
                driver.state.getGraveyard(player).contains(beast) shouldBe true
            }
            withClue("4 damage trampled over to the defending player") {
                driver.getLifeTotal(opponent) shouldBe 16
            }
            withClue("the Mask still triggered and offered the draw") {
                maskOffered shouldBe true
            }
            withClue("accepting drew two cards") {
                driver.state.getLibrary(player).size shouldBe libraryBefore - 2
            }
        }

        test("combat damage from a non-equipped creature does not trigger the Mask") {
            val driver = createDriver()
            val player = driver.activePlayer!!
            val opponent = driver.getOpponent(player)
            val attacker = driver.putCreatureOnBattlefield(player, "Centaur Courser")
            val equippedHost = driver.putCreatureOnBattlefield(player, "Centaur Courser")
            val mask = driver.putPermanentOnBattlefield(player, "Mask of Memory")
            equip(driver, player, mask, equippedHost)
            driver.removeSummoningSickness(attacker)

            val libraryBefore = driver.state.getLibrary(player).size
            val handBefore = driver.state.getHand(player).size
            driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
            driver.declareAttackers(player, listOf(attacker), opponent)
            driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
            driver.passPriorityUntil(Step.COMBAT_DAMAGE)
            while (driver.state.stack.isNotEmpty()) driver.bothPass()

            driver.getLifeTotal(opponent) shouldBe 17
            driver.state.getLibrary(player).size shouldBe libraryBefore
            driver.state.getHand(player).size shouldBe handBefore
        }
    }
}
