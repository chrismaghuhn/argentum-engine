package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Termination Facilitator (J22 #28).
 *
 * Oracle: "{T}: Put a bounty counter on target creature or planeswalker. Activate only as a
 * sorcery. Whenever a creature or planeswalker an opponent controls with a bounty counter on it
 * is dealt damage, destroy it."
 */
class TerminationFacilitatorScenarioTest : ScenarioTestBase() {

    private val testWalker = card("Termination Test Walker") {
        manaCost = "{2}"
        typeLine = "Legendary Planeswalker — Tester"
        startingLoyalty = 3
    }

    private val testCreature = card("Termination Test Creature") {
        manaCost = "{3}"
        typeLine = "Creature — Tester"
        power = 4
        toughness = 4
    }

    init {
        cardRegistry.register(testWalker)
        cardRegistry.register(testCreature)

        fun bountyCount(game: TestGame, permanent: com.wingedsheep.sdk.model.EntityId): Int =
            game.state.getEntity(permanent)?.get<CountersComponent>()?.getCount(CounterType.BOUNTY) ?: 0

        fun abilityId(): AbilityId =
            cardRegistry.requireCard("Termination Facilitator").activatedAbilities.single().id

        test("marks a creature or planeswalker and only allows the activation at sorcery speed") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Termination Facilitator")
                .withCardOnBattlefield(2, "Termination Test Walker")
                .withCardOnBattlefield(2, "Mountain")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val facilitator = game.findPermanent("Termination Facilitator")!!
            val walker = game.findPermanent("Termination Test Walker")!!
            val marked = game.execute(
                ActivateAbility(
                    playerId = game.player1Id,
                    sourceId = facilitator,
                    abilityId = abilityId(),
                    targets = listOf(ChosenTarget.Permanent(walker)),
                ),
            )
            withClue("the creature-or-planeswalker target should be legal: ${marked.error}") {
                marked.error shouldBe null
            }
            game.resolveStack()
            bountyCount(game, walker) shouldBe 1

            val tooEarly = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Termination Facilitator")
                .withCardOnBattlefield(2, "Termination Test Walker")
                .withActivePlayer(1)
                .inPhase(Phase.BEGINNING, Step.UPKEEP)
                .build()
            val tooEarlyFacilitator = tooEarly.findPermanent("Termination Facilitator")!!
            val tooEarlyWalker = tooEarly.findPermanent("Termination Test Walker")!!
            tooEarly.execute(
                ActivateAbility(
                    playerId = tooEarly.player1Id,
                    sourceId = tooEarlyFacilitator,
                    abilityId = abilityId(),
                    targets = listOf(ChosenTarget.Permanent(tooEarlyWalker)),
                ),
            ).error shouldNotBe null
        }

        test("destroys a marked opponent permanent when it is dealt damage") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Termination Facilitator")
                .withCardOnBattlefield(2, "Hill Giant")
                .withCardInHand(1, "Lightning Bolt")
                .withLandsOnBattlefield(1, "Mountain", 1)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val facilitator = game.findPermanent("Termination Facilitator")!!
            val giant = game.findPermanent("Hill Giant")!!
            game.execute(
                ActivateAbility(
                    playerId = game.player1Id,
                    sourceId = facilitator,
                    abilityId = abilityId(),
                    targets = listOf(ChosenTarget.Permanent(giant)),
                ),
            ).error shouldBe null
            game.resolveStack()
            bountyCount(game, giant) shouldBe 1

            game.castSpell(1, "Lightning Bolt", targetId = giant).error shouldBe null
            game.resolveStack()

            withClue("damage to the marked opposing creature should destroy it") {
                game.findPermanent("Hill Giant") shouldBe null
            }
            game.isInGraveyard(2, "Hill Giant") shouldBe true
        }

        test("does not destroy an unmarked creature or a marked creature you control") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Termination Facilitator")
                .withCardOnBattlefield(1, "Termination Test Creature")
                .withCardOnBattlefield(2, "Termination Test Creature")
                .withCardInHand(1, "Lightning Bolt")
                .withCardInHand(1, "Lightning Bolt")
                .withLandsOnBattlefield(1, "Mountain", 2)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val facilitator = game.findPermanent("Termination Facilitator")!!
            val ownGiant = game.findPermanents("Termination Test Creature")[0]
            val opponentGiant = game.findPermanents("Termination Test Creature")[1]
            game.execute(
                ActivateAbility(
                    playerId = game.player1Id,
                    sourceId = facilitator,
                    abilityId = abilityId(),
                    targets = listOf(ChosenTarget.Permanent(ownGiant)),
                ),
            ).error shouldBe null
            game.resolveStack()
            bountyCount(game, ownGiant) shouldBe 1

            game.castSpell(1, "Lightning Bolt", targetId = ownGiant).error shouldBe null
            game.resolveStack()
            game.findPermanent("Termination Test Creature") shouldNotBe null

            game.castSpell(1, "Lightning Bolt", targetId = opponentGiant).error shouldBe null
            game.resolveStack()
            withClue("damage to an unmarked opposing creature should not invoke the trigger") {
                game.findPermanent("Termination Test Creature") shouldNotBe null
            }
        }
    }
}
