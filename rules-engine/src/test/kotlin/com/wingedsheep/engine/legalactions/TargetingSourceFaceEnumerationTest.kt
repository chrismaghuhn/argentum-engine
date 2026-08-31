package com.wingedsheep.engine.legalactions

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardLayout
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.ProtectionScope
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Alternative spell faces are the source of targeting characteristics while that face is cast.
 * Enumeration and strict validation must not fall back to the primary card characteristics.
 */
class TargetingSourceFaceEnumerationTest : FunSpec({
    val splitSpell = card("Targeting Source Face Split Spell") {
        manaCost = "{R}"
        colorIdentity = "R"
        typeLine = "Instant"
        layout = CardLayout.SPLIT
        face("Red Half") {
            manaCost = "{R}"
            typeLine = "Instant"
            oracleText = "Target creature gets +1/+0 until end of turn."
            spell {
                target = Targets.Creature
                effect = Effects.ModifyStats(1, 0, EffectTarget.ContextTarget(0))
            }
        }
        face("Blue Half") {
            manaCost = "{U}"
            typeLine = "Instant"
            oracleText = "Target creature gets +1/+0 until end of turn."
            spell {
                target = Targets.Creature
                effect = Effects.ModifyStats(1, 0, EffectTarget.ContextTarget(0))
            }
        }
    }

    val blueProtectedTarget = card("Targeting Source Face Blue-Protected Creature") {
        manaCost = "{1}{U}"
        colorIdentity = "U"
        typeLine = "Creature — Soldier"
        power = 2
        toughness = 2
        keywordAbility(KeywordAbility.Protection(ProtectionScope.Color(Color.BLUE)))
    }

    test("the selected split face supplies source characteristics to enumeration and strict validation") {
        val driver = GameTestDriver().apply {
            registerCards(TestCards.all + listOf(splitSpell, blueProtectedTarget))
            initMirrorMatch(
                deck = Deck.of("Island" to 40),
                skipMulligans = true,
                startingPlayer = 0,
            )
        }
        val caster = driver.activePlayer!!
        val opponent = driver.getOpponent(caster)
        val spellId = driver.putCardInHand(caster, splitSpell.name)
        driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        val protectedTargetId = driver.putCreatureOnBattlefield(opponent, blueProtectedTarget.name)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        driver.giveMana(caster, Color.BLUE)

        val action = driver.legalActions(caster).single { candidate ->
            val cast = candidate.action as? CastSpell
            cast?.cardId == spellId && cast.faceIndex == 1
        }
        val validTargets = action.validTargets.shouldNotBeNull()
        val ordinaryTargetId = validTargets.first { it != protectedTargetId }
        validTargets shouldContain ordinaryTargetId
        validTargets shouldNotContain protectedTargetId

        val beforeState = driver.state
        val beforeEvents = driver.events
        val result = driver.submit(
            CastSpell(
                playerId = caster,
                cardId = spellId,
                faceIndex = 1,
                targets = listOf(ChosenTarget.Permanent(protectedTargetId)),
            )
        )

        result.error shouldContain "protection from blue"
        driver.state shouldBe beforeState
        driver.events shouldBe beforeEvents
    }
})
