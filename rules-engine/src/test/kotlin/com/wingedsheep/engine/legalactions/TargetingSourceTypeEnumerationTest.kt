package com.wingedsheep.engine.legalactions

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.state.components.battlefield.CantBeTargetedByOpponentAbilitiesComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull

/**
 * The source type is part of target enumeration semantics: a permanent may reject an opponent's
 * ability while remaining a legal target for that opponent's spell.
 */
class TargetingSourceTypeEnumerationTest : FunSpec({
    val abilitySource = card("Targeting Source Type Ability") {
        manaCost = "{1}{R}"
        colorIdentity = "R"
        typeLine = "Creature — Wizard"
        power = 1
        toughness = 1
        oracleText = "{T}: Target creature gets +1/+0 until end of turn."
        activatedAbility {
            cost = Costs.Tap
            target = Targets.Creature
            effect = Effects.ModifyStats(1, 0, com.wingedsheep.sdk.scripting.targets.EffectTarget.ContextTarget(0))
        }
    }

    val spellSource = card("Targeting Source Type Spell") {
        manaCost = "{R}"
        colorIdentity = "R"
        typeLine = "Instant"
        oracleText = "Target creature gets +1/+0 until end of turn."
        spell {
            target = Targets.Creature
            effect = Effects.ModifyStats(1, 0, com.wingedsheep.sdk.scripting.targets.EffectTarget.ContextTarget(0))
        }
    }

    val ordinaryTarget = card("Targeting Source Type Ordinary Creature") {
        manaCost = "{1}{G}"
        colorIdentity = "G"
        typeLine = "Creature — Bear"
        power = 2
        toughness = 2
    }

    val guardedTarget = card("Targeting Source Type Guarded Creature") {
        manaCost = "{1}{W}"
        colorIdentity = "W"
        typeLine = "Creature — Soldier"
        power = 2
        toughness = 2
    }

    fun fixture(): SourceTypeFixture {
        val driver = GameTestDriver().apply {
            registerCards(TestCards.all + listOf(abilitySource, spellSource, ordinaryTarget, guardedTarget))
            initMirrorMatch(
                deck = Deck.of("Mountain" to 40),
                skipMulligans = true,
                startingPlayer = 0,
            )
        }
        val caster = driver.activePlayer!!
        val opponent = driver.getOpponent(caster)
        val abilitySourceId = driver.putCreatureOnBattlefield(caster, abilitySource.name)
        driver.removeSummoningSickness(abilitySourceId)
        val ordinaryTargetId = driver.putCreatureOnBattlefield(opponent, ordinaryTarget.name)
        val guardedTargetId = driver.putCreatureOnBattlefield(opponent, guardedTarget.name)
        driver.addComponent(guardedTargetId, CantBeTargetedByOpponentAbilitiesComponent())
        val spellId = driver.putCardInHand(caster, spellSource.name)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        driver.giveMana(caster, Color.RED)
        return SourceTypeFixture(driver, caster, abilitySourceId, spellId, ordinaryTargetId, guardedTargetId)
    }

    test("an opponent activated ability excludes a permanent guarded from opponent abilities") {
        val fixture = fixture()
        val action = fixture.driver.legalActions(fixture.caster).single { candidate ->
            (candidate.action as? ActivateAbility)?.sourceId == fixture.abilitySource
        }
        val validTargets = action.validTargets.shouldNotBeNull()

        validTargets shouldContain fixture.ordinaryTarget
        validTargets shouldNotContain fixture.guardedTarget
    }

    test("an opponent spell keeps a permanent guarded from opponent abilities as a target") {
        val fixture = fixture()
        val action = fixture.driver.legalActions(fixture.caster).single { candidate ->
            (candidate.action as? CastSpell)?.cardId == fixture.spell
        }
        val validTargets = action.validTargets.shouldNotBeNull()

        validTargets shouldContain fixture.guardedTarget
    }
})

private data class SourceTypeFixture(
    val driver: GameTestDriver,
    val caster: com.wingedsheep.sdk.model.EntityId,
    val abilitySource: com.wingedsheep.sdk.model.EntityId,
    val spell: com.wingedsheep.sdk.model.EntityId,
    val ordinaryTarget: com.wingedsheep.sdk.model.EntityId,
    val guardedTarget: com.wingedsheep.sdk.model.EntityId,
)
