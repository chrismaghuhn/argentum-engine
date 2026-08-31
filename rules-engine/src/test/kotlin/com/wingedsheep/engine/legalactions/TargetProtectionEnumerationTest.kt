package com.wingedsheep.engine.legalactions

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.ProtectionScope
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain

/**
 * RED characterization for source-aware protection during activated-ability target enumeration.
 * The enumerator currently publishes a protected permanent, while the authoritative handler
 * rejects the same submitted target. No production code is changed by this characterization.
 */
class TargetProtectionEnumerationTest : FunSpec({
    val redTargetingSource = card("Target Protection Red Source") {
        manaCost = "{1}{R}"
        colorIdentity = "R"
        typeLine = "Creature — Wizard"
        power = 1
        toughness = 1
        oracleText = "{T}: Target creature gets +1/+0 until end of turn."
        activatedAbility {
            cost = Costs.Tap
            target = Targets.Creature
            effect = Effects.ModifyStats(1, 0, EffectTarget.ContextTarget(0))
        }
    }

    val ordinaryTarget = card("Target Protection Ordinary Creature") {
        manaCost = "{1}{G}"
        colorIdentity = "G"
        typeLine = "Creature — Bear"
        power = 2
        toughness = 2
    }

    val redProtectedTarget = card("Target Protection Red-Protected Creature") {
        manaCost = "{1}{W}"
        colorIdentity = "W"
        typeLine = "Creature — Soldier"
        power = 2
        toughness = 2
        keywordAbility(KeywordAbility.Protection(ProtectionScope.Color(Color.RED)))
    }

    fun fixture(): TargetProtectionFixture {
        val driver = GameTestDriver().apply {
            registerCards(TestCards.all + listOf(redTargetingSource, ordinaryTarget, redProtectedTarget))
            initMirrorMatch(
                deck = Deck.of("Plains" to 40),
                skipMulligans = true,
                startingPlayer = 0,
            )
            passPriorityUntil(Step.PRECOMBAT_MAIN)
        }
        val player = driver.activePlayer!!
        val source = driver.putCreatureOnBattlefield(player, redTargetingSource.name)
        driver.removeSummoningSickness(source)
        val ordinary = driver.putCreatureOnBattlefield(player, ordinaryTarget.name)
        val protected = driver.putCreatureOnBattlefield(player, redProtectedTarget.name)
        return TargetProtectionFixture(driver, player, source, ordinary, protected)
    }

    test("target enumeration excludes a creature protected from the source color") {
        val fixture = fixture()
        val action = fixture.driver.legalActions(fixture.player)
            .single { candidate ->
                val activate = candidate.action as? ActivateAbility
                activate?.sourceId == fixture.source
            }
        val validTargets = action.validTargets.shouldNotBeNull()

        validTargets shouldContain fixture.ordinaryTarget
        // Characterization: the current enumerator incorrectly includes the protected target.
        validTargets shouldContain fixture.protectedTarget
        println(
            "RULES_TARGET_PROTECTION_ENUMERATION_01 " +
                "source=${fixture.source} ordinary=${fixture.ordinaryTarget} " +
                "protected=${fixture.protectedTarget} actualValidTargets=$validTargets",
        )
        // RED: TargetEnumerationUtils currently omits the source-aware protection check.
        validTargets shouldNotContain fixture.protectedTarget
    }

    test("the authoritative submit rejects the same protected target") {
        val fixture = fixture()
        val abilityId = redTargetingSource.activatedAbilities.single().id
        val beforeState = fixture.driver.state
        val beforeEvents = fixture.driver.events
        val result = fixture.driver.submit(
            ActivateAbility(
                playerId = fixture.player,
                sourceId = fixture.source,
                abilityId = abilityId,
                targets = listOf(ChosenTarget.Permanent(fixture.protectedTarget)),
            )
        )

        result.error shouldNotBe null
        result.error shouldContain "protection from red"
        fixture.driver.state shouldBe beforeState
        fixture.driver.events shouldBe beforeEvents
    }
})

private data class TargetProtectionFixture(
    val driver: GameTestDriver,
    val player: EntityId,
    val source: EntityId,
    val ordinaryTarget: EntityId,
    val protectedTarget: EntityId,
)
