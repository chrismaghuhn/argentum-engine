package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CommanderComponent
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The Commander "command zone instead of hand/library" choice (CR 903.9b) raised by a *target*
 * of a resolving spell, not by the spell itself.
 *
 * Chaos Warp-shaped: the spell shuffles an opposing commander into its owner's library and then
 * keeps resolving. The owner's yes/no replacement prompt pauses the spell mid-resolution; once it
 * is answered, the spell must finish and leave the stack. It used to be put back onto the stack
 * while the prompt was pending (a rule meant only for the spell's own zone move), so after the
 * spell reached the graveyard a hollow copy of its id stayed on the stack and the next resolution
 * failed with "Unknown stack item type".
 */
class CommanderZoneChoiceDuringSpellResolutionTest : FunSpec({

    // {2}{R} instant: "Target creature's owner shuffles it into their library. Draw a card."
    val tuckTester = card("Tuck Tester") {
        manaCost = "{2}{R}"
        colorIdentity = "R"
        typeLine = "Instant"
        oracleText = "The owner of target creature shuffles it into their library. Draw a card."
        spell {
            val creature = target(TargetFilter.Creature)
            effect = Effects.Composite(
                listOf(
                    Effects.ShuffleIntoLibrary(creature),
                    Effects.DrawCards(1),
                )
            )
        }
    }

    data class Setup(val driver: GameTestDriver, val caster: EntityId, val owner: EntityId, val commander: EntityId, val spell: EntityId)

    fun castTuckAtCommander(): Setup {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(tuckTester)
        driver.initMirrorMatch(deck = Deck.of("Mountain" to 40), startingLife = 40)
        driver.replaceState(driver.state.copy(format = Format.Commander()))
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val caster = driver.activePlayer!!
        val owner = driver.getOpponent(caster)
        val commander = driver.putCreatureOnBattlefield(owner, "Glory Seeker")
        driver.replaceState(
            driver.state.updateEntity(commander) { c -> c.with(CommanderComponent(ownerId = owner)) }
        )

        val spell = driver.putCardInHand(caster, "Tuck Tester")
        driver.giveMana(caster, Color.RED, 3)
        driver.castSpell(caster, spell, listOf(commander)).error shouldBe null
        driver.bothPass()

        val prompt = driver.pendingDecision
        prompt.shouldBeInstanceOf<YesNoDecision>()
        prompt.playerId shouldBe owner
        return Setup(driver, caster, owner, commander, spell)
    }

    fun Setup.assertSpellFinished() {
        val state = driver.state
        state.stack.shouldBeEmpty()
        state.getGraveyard(caster) shouldContain spell
        state.getEntity(spell)!!.has<SpellOnStackComponent>() shouldBe false
        // The game keeps going: the next priority pass must not try to resolve a hollow stack object.
        driver.bothPass().error shouldBe null
    }

    test("owner moves the tucked commander to the command zone and the spell finishes resolving") {
        val setup = castTuckAtCommander()
        val handBefore = setup.driver.state.getHand(setup.caster).size

        setup.driver.submitYesNo(setup.owner, true).error shouldBe null

        setup.driver.state.getZone(ZoneKey(setup.owner, Zone.COMMAND)) shouldContain setup.commander
        setup.driver.state.getLibrary(setup.owner) shouldNotContain setup.commander
        setup.driver.state.getHand(setup.caster).size shouldBe handBefore + 1
        setup.assertSpellFinished()
    }

    test("owner lets the commander be shuffled away and the spell finishes resolving") {
        val setup = castTuckAtCommander()

        setup.driver.submitYesNo(setup.owner, false).error shouldBe null

        setup.driver.state.getLibrary(setup.owner) shouldContain setup.commander
        setup.assertSpellFinished()
    }
})
