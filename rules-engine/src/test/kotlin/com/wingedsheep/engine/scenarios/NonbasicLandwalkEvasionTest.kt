package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContainIgnoringCase

/** Regression coverage for nonbasic landwalk on lands with basic-land subtypes. */
class NonbasicLandwalkEvasionTest : FunSpec({

    val NonbasicWalker = CardDefinition.creature(
        name = "Nonbasic Walker",
        manaCost = ManaCost.ZERO,
        subtypes = setOf(Subtype("Human")),
        power = 2,
        toughness = 2,
        oracleText = "Nonbasic landwalk",
        keywords = setOf(Keyword.NONBASIC_LANDWALK),
    )

    val NonbasicDual = CardDefinition(
        name = "Nonbasic Dual",
        manaCost = ManaCost.ZERO,
        typeLine = TypeLine(
            cardTypes = setOf(CardType.LAND),
            subtypes = setOf(Subtype.MOUNTAIN, Subtype.FOREST),
        ),
    )

    test("nonbasic landwalk sees a dual land with basic-land subtypes as nonbasic") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + NonbasicWalker + NonbasicDual)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40))

        val attackerPlayer = driver.activePlayer!!
        val defendingPlayer = driver.getOpponent(attackerPlayer)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val attacker = driver.putCreatureOnBattlefield(attackerPlayer, "Nonbasic Walker")
        val blocker = driver.putCreatureOnBattlefield(defendingPlayer, "Grizzly Bears")
        driver.removeSummoningSickness(attacker)
        driver.putPermanentOnBattlefield(defendingPlayer, "Nonbasic Dual")

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(attackerPlayer, listOf(attacker), defendingPlayer).isSuccess shouldBe true
        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)

        val result = driver.submitExpectFailure(
            com.wingedsheep.engine.core.DeclareBlockers(
                defendingPlayer,
                mapOf(blocker to listOf(attacker)),
            )
        )

        result.isSuccess shouldBe false
        result.error shouldContainIgnoringCase "nonbasic landwalk"
        result.error shouldContainIgnoringCase "cannot be blocked"
    }
})
