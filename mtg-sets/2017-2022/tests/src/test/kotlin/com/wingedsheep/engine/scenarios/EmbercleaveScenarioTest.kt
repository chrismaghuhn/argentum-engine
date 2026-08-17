package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.mechanics.layers.StateProjector
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Embercleave (ELD #120) — {4}{R}{R} Legendary Artifact — Equipment.
 *
 * Flash; costs {1} less for each attacking creature you control; its ETB attaches it to a target
 * creature you control; the equipped creature gets +1/+1, double strike, and trample; equip {3}.
 */
class EmbercleaveScenarioTest : FunSpec({

    val projector = StateProjector()

    fun createDriver(): GameTestDriver = GameTestDriver().also {
        it.registerCards(TestCards.all)
    }

    fun castAction(driver: GameTestDriver, player: EntityId, cardId: EntityId) =
        driver.legalActions(player).single {
            it.actionType == "CastSpell" &&
                (it.action as? CastSpell)?.cardId == cardId
        }

    fun setupAttackers(attackerCount: Int): Pair<GameTestDriver, String> {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Mountain" to 30), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val me = driver.activePlayer!!
        val opponent = if (me == driver.player1) driver.player2 else driver.player1
        val attackerNames = listOf("Grizzly Bears", "Hill Giant")
        val attackers = attackerNames.take(attackerCount).map { name ->
            driver.putCreatureOnBattlefield(me, name).also(driver::removeSummoningSickness)
        }
        driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
            .also(driver::removeSummoningSickness)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(me, attackers, opponent).isSuccess shouldBe true

        val cleave = driver.putCardInHand(me, "Embercleave")
        driver.giveMana(me, Color.RED, 6)
        return driver to castAction(driver, me, cleave).manaCostString!!
    }

    test("flash is available after attackers are declared and reduction counts only your attackers") {
        setupAttackers(1).second shouldBe "{3}{R}{R}"
        setupAttackers(2).second shouldBe "{2}{R}{R}"
    }

    test("ETB targets only a creature you control, attaches, and grants the printed bonuses") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Mountain" to 30), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val me = driver.activePlayer!!
        val opponent = if (me == driver.player1) driver.player2 else driver.player1
        val host = driver.putCreatureOnBattlefield(me, "Grizzly Bears")
        val opponentCreature = driver.putCreatureOnBattlefield(opponent, "Hill Giant")
        val cleave = driver.putCardInHand(me, "Embercleave")
        driver.giveMana(me, Color.RED, 6)

        driver.submitSuccess(
            CastSpell(
                playerId = me,
                cardId = cleave,
                paymentStrategy = PaymentStrategy.FromPool,
            )
        )
        driver.bothPass()
        driver.bothPass()

        driver.submitTargetSelection(me, listOf(opponentCreature)).isSuccess shouldBe false
        driver.submitTargetSelection(me, listOf(host)).isSuccess shouldBe true
        driver.bothPass()

        val equipment = driver.findPermanent(me, "Embercleave")!!
        driver.state.getEntity(equipment)?.get<AttachedToComponent>()?.targetId shouldBe host
        projector.getProjectedPower(driver.state, host) shouldBe 3
        projector.getProjectedToughness(driver.state, host) shouldBe 3
        projector.project(driver.state).hasKeyword(host, Keyword.DOUBLE_STRIKE) shouldBe true
        projector.project(driver.state).hasKeyword(host, Keyword.TRAMPLE) shouldBe true
    }

    test("equip costs {3}, rejects an opponent creature, and moves the bonuses to your creature") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Mountain" to 30), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val me = driver.activePlayer!!
        val opponent = if (me == driver.player1) driver.player2 else driver.player1
        val firstHost = driver.putCreatureOnBattlefield(me, "Grizzly Bears")
        val secondHost = driver.putCreatureOnBattlefield(me, "Grizzly Bears")
        val opponentCreature = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
        val cleave = driver.putCardInHand(me, "Embercleave")
        driver.giveMana(me, Color.RED, 6)
        driver.submitSuccess(
            CastSpell(
                playerId = me,
                cardId = cleave,
                paymentStrategy = PaymentStrategy.FromPool,
            )
        )
        driver.bothPass()
        driver.bothPass()
        driver.submitTargetSelection(me, listOf(firstHost)).isSuccess shouldBe true
        driver.bothPass()

        val equipment = driver.findPermanent(me, "Embercleave")!!
        val equipAbility = driver.cardRegistry.requireCard("Embercleave")
            .activatedAbilities.single { it.isEquipAbility }
        driver.giveColorlessMana(me, 3)
        driver.legalActions(me).single {
            it.action is ActivateAbility &&
                (it.action as ActivateAbility).sourceId == equipment
        }.manaCostString shouldBe "{3}"

        driver.submit(
            ActivateAbility(
                playerId = me,
                sourceId = equipment,
                abilityId = equipAbility.id,
                targets = listOf(ChosenTarget.Permanent(opponentCreature)),
                paymentStrategy = PaymentStrategy.FromPool,
            )
        ).isSuccess shouldBe false

        driver.submitSuccess(
            ActivateAbility(
                playerId = me,
                sourceId = equipment,
                abilityId = equipAbility.id,
                targets = listOf(ChosenTarget.Permanent(secondHost)),
                paymentStrategy = PaymentStrategy.FromPool,
            )
        )
        driver.bothPass()

        driver.state.getEntity(equipment)?.get<AttachedToComponent>()?.targetId shouldBe secondHost
        projector.getProjectedPower(driver.state, firstHost) shouldBe 2
        projector.getProjectedPower(driver.state, secondHost) shouldBe 3
        projector.project(driver.state).hasKeyword(secondHost, Keyword.DOUBLE_STRIKE) shouldBe true
        projector.project(driver.state).hasKeyword(secondHost, Keyword.TRAMPLE) shouldBe true
    }
})
