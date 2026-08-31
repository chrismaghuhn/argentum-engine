package com.wingedsheep.engine.legalactions

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.state.components.identity.PlayWithoutPayingCostComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.permissions.MayPlayPermission
import com.wingedsheep.engine.state.permissions.addMayPlayPermission
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.ProtectionScope
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** A transformed may-play permission must use the back face for target legality. */
class TargetingSourceTransformedPermissionEnumerationTest : FunSpec({
    val transformedFront = card("Targeting Source Transformed Permission Front") {
        manaCost = "{R}"
        colorIdentity = "R"
        typeLine = "Instant"
        spell {
            target = Targets.Creature
            effect = Effects.ModifyStats(1, 0, EffectTarget.ContextTarget(0))
        }
    }

    val transformedBack = card("Targeting Source Transformed Permission Back") {
        manaCost = "{U}"
        colorIdentity = "U"
        typeLine = "Instant"
        spell {
            target = Targets.Creature
            effect = Effects.ModifyStats(1, 0, EffectTarget.ContextTarget(0))
        }
    }

    val transformedSpell = transformedFront.copy(backFace = transformedBack)

    val blueProtectedTarget = card("Targeting Source Transformed Permission Blue-Protected Creature") {
        manaCost = "{1}{U}"
        colorIdentity = "U"
        typeLine = "Creature — Soldier"
        power = 2
        toughness = 2
        keywordAbility(KeywordAbility.Protection(ProtectionScope.Color(Color.BLUE)))
    }

    test("a cast-transformed may-play permission uses back-face characteristics for enumeration and strict validation") {
        val driver = GameTestDriver().apply {
            registerCards(TestCards.all + listOf(transformedSpell, blueProtectedTarget))
            initMirrorMatch(
                deck = Deck.of("Mountain" to 40),
                skipMulligans = true,
                startingPlayer = 0,
            )
        }
        val caster = driver.activePlayer!!
        val opponent = driver.getOpponent(caster)
        val spellId = driver.putCardInExile(caster, transformedSpell.name)
        driver.addComponent(spellId, PlayWithoutPayingCostComponent(controllerId = caster))
        driver.replaceState(
            driver.state.addMayPlayPermission(
                MayPlayPermission(
                    id = com.wingedsheep.sdk.model.EntityId.generate(),
                    cardIds = setOf(spellId),
                    controllerId = caster,
                    castTransformed = true,
                    castColorRestriction = Color.BLUE,
                    timestamp = driver.state.timestamp,
                )
            )
        )
        driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        val protectedTargetId = driver.putCreatureOnBattlefield(opponent, blueProtectedTarget.name)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val action = driver.legalActions(caster).single { candidate ->
            (candidate.action as? CastSpell)?.cardId == spellId
        }
        val validTargets = action.validTargets.shouldNotBeNull()
        val ordinaryTargetId = validTargets.first { it != protectedTargetId }
        validTargets shouldContain ordinaryTargetId
        println(
            "TargetingSourceTransformedPermissionEnumerationTest " +
                "front=${transformedFront.name} back=${transformedBack.name} " +
                "actualValidTargets=$validTargets protected=$protectedTargetId"
        )

        // Desired behavior — RED before the enumerator receives castTransformed's back face.
        validTargets shouldNotContain protectedTargetId

        val beforeState = driver.state
        val beforeEvents = driver.events
        val result = driver.submit(
            CastSpell(
                playerId = caster,
                cardId = spellId,
                targets = listOf(ChosenTarget.Permanent(ordinaryTargetId)),
            )
        )

        // Desired behavior — RED before the strict handler reads castTransformed's back face.
        result.isSuccess shouldBe true
        result.error shouldBe null
        driver.state shouldNotBe beforeState
        driver.events shouldNotBe beforeEvents
    }
})
