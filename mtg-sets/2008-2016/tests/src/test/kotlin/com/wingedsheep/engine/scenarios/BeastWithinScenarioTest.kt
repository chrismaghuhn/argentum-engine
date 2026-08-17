package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.nph.NewPhyrexiaSet
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Beast Within (NPH #103) — destroy any target permanent; its controller creates a 3/3 green
 * Beast creature token.
 */
class BeastWithinScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val canonical = NewPhyrexiaSet.cards.singleOrNull { it.name == "Beast Within" }
            ?: error("NPH canonical Beast Within is not registered")
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all.filterNot { it.name == "Beast Within" } + canonical)
        driver.initMirrorMatch(
            deck = Deck.of("Forest" to 40),
            skipMulligans = true,
            startingLife = 20,
        )
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun resolveStack(driver: GameTestDriver) {
        var guard = 0
        while (driver.state.stack.isNotEmpty() && !driver.isPaused && guard++ < 30) {
            driver.bothPass()
        }
    }

    fun castBeastWithin(driver: GameTestDriver, target: com.wingedsheep.sdk.model.EntityId) {
        val caster = driver.activePlayer!!
        val spell = driver.putCardInHand(caster, "Beast Within")
        driver.giveMana(caster, Color.GREEN)
        driver.giveColorlessMana(caster, 2)

        val result = driver.castSpell(caster, spell, listOf(target))
        result.error shouldBe null
        resolveStack(driver)
    }

    test("destroys any permanent and gives its controller a 3/3 green Beast") {
        val driver = createDriver()
        val caster = driver.activePlayer!!
        val opponent = driver.getOpponent(caster)
        val target = driver.putLandOnBattlefield(opponent, "Forest")

        castBeastWithin(driver, target)

        driver.state.getZone(opponent, com.wingedsheep.sdk.core.Zone.BATTLEFIELD) shouldNotContain target
        driver.getGraveyard(opponent) shouldContain target

        val beasts = driver.getPermanents(opponent).filter { id ->
            driver.state.getEntity(id)?.get<CardComponent>()?.name == "Beast Token"
        }
        beasts.size shouldBe 1

        val beast = beasts.single()
        val beastCard = driver.state.getEntity(beast)?.get<CardComponent>()
        beastCard shouldNotBe null
        beastCard!!.colors shouldBe setOf(Color.GREEN)
        beastCard.typeLine.isCreature shouldBe true
        beastCard.typeLine.subtypes shouldContain Subtype("Beast")
        driver.state.projectedState.getPower(beast) shouldBe 3
        driver.state.projectedState.getToughness(beast) shouldBe 3
        driver.state.getEntity(beast)?.get<ControllerComponent>()?.playerId shouldBe opponent
        driver.getPermanents(caster).count { id ->
            driver.state.getEntity(id)?.get<CardComponent>()?.name == "Beast Token"
        } shouldBe 0
    }

    test("rejects a player as an illegal target") {
        val driver = createDriver()
        val caster = driver.activePlayer!!
        val spell = driver.putCardInHand(caster, "Beast Within")
        driver.giveMana(caster, Color.GREEN)
        driver.giveColorlessMana(caster, 2)

        val result = driver.castSpell(caster, spell, listOf(caster))

        result.error shouldNotBe null
        driver.state.getZone(caster, com.wingedsheep.sdk.core.Zone.HAND) shouldContain spell
        driver.state.stack shouldBe emptyList()
    }

    test("rejects casting when no permanent target exists") {
        val driver = createDriver()
        val caster = driver.activePlayer!!
        val spell = driver.putCardInHand(caster, "Beast Within")
        driver.giveMana(caster, Color.GREEN)
        driver.giveColorlessMana(caster, 2)

        val result = driver.castSpell(caster, spell)

        result.error shouldNotBe null
        driver.state.getZone(caster, com.wingedsheep.sdk.core.Zone.HAND) shouldContain spell
        driver.state.stack shouldBe emptyList()
    }
})
