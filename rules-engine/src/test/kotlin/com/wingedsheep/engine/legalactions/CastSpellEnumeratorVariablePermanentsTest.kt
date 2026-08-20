package com.wingedsheep.engine.legalactions

import com.wingedsheep.engine.legalactions.support.setupP1
import com.wingedsheep.engine.legalactions.support.shouldNotContainCastOf
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardScript
import com.wingedsheep.sdk.scripting.GameObjectFilter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/** Normal hand-cast publication for variable permanent additional costs. */
class CastSpellEnumeratorVariablePermanentsTest : FunSpec({

    fun variableSacrificeSpell(name: String, minCount: Int) = CardDefinition.instant(
        name = name,
        manaCost = ManaCost.parse("{1}"),
        oracleText = "As an additional cost to cast this spell, sacrifice any number of creatures.",
        script = CardScript(
            additionalCosts = listOf(
                Costs.additional.SacrificePermanents(
                    filter = GameObjectFilter.Creature,
                    minCount = minCount,
                )
            )
        )
    )

    fun driver(
        spell: CardDefinition,
        battlefield: List<String> = emptyList(),
    ) = setupP1(
        hand = listOf(spell.name),
        battlefield = battlefield,
        extraSetCards = listOf(spell),
    )

    fun castOf(driver: com.wingedsheep.engine.legalactions.support.EnumerationTestDriver, name: String) =
        driver.enumerateFor(driver.player1).castActionsFor(name).single()

    test("minimum zero publishes an explicit empty candidate domain") {
        val spell = variableSacrificeSpell("Variable Spell Cost Enumerator Optional Probe", minCount = 0)
        val driver = driver(spell, battlefield = listOf("Forest"))

        val cast = castOf(driver, spell.name)
        cast.affordable shouldBe true

        val costInfo = cast.additionalCostInfo.shouldNotBeNull()
        costInfo.costType shouldBe "VariableSacrifice"
        costInfo.sacrificeCount shouldBe 0
        costInfo.sacrificeMinCount shouldBe 0
        costInfo.sacrificeMaxCount shouldBe 0
        costInfo.validSacrificeTargets shouldBe emptyList()
    }

    test("minimum positive is not payable when the filtered domain is empty") {
        val spell = variableSacrificeSpell("Variable Spell Cost Enumerator Required Probe", minCount = 1)
        val driver = driver(spell, battlefield = listOf("Forest"))

        driver.enumerateFor(driver.player1) shouldNotContainCastOf spell.name
    }

    test("minimum positive is not payable when the domain is smaller than the minimum") {
        val spell = variableSacrificeSpell("Variable Spell Cost Enumerator Insufficient Probe", minCount = 2)
        val driver = driver(spell, battlefield = listOf("Grizzly Bears"))

        driver.enumerateFor(driver.player1) shouldNotContainCastOf spell.name
    }

    test("publishes all actor-owned filtered candidates in deterministic order") {
        val spell = variableSacrificeSpell("Variable Spell Cost Enumerator Domain Probe", minCount = 0)
        val driver = driver(spell, battlefield = listOf("Grizzly Bears", "Forest", "Grizzly Bears", "Grizzly Bears"))
        val opponentCreature = driver.game.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val expected = driver.game.getCreatures(driver.player1)

        val first = castOf(driver, spell.name).additionalCostInfo.shouldNotBeNull()
        val second = castOf(driver, spell.name).additionalCostInfo.shouldNotBeNull()

        first.validSacrificeTargets shouldBe expected
        first.validSacrificeTargets shouldNotContain opponentCreature
        first.sacrificeMaxCount shouldBe expected.size
        second.validSacrificeTargets shouldBe first.validSacrificeTargets
    }
})
