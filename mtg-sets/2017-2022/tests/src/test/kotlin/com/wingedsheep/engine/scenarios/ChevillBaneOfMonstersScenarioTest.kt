package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.AbilityFizzledEvent
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.Counters
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardScript
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.serialization.CardLoader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Focused conformance coverage for Chevill, Bane of Monsters (IKO #181).
 *
 * The target is deliberately the current Oracle wording's "creature or planeswalker", rather
 * than the broader "permanent" shorthand used by the original curriculum sketch.
 */
class ChevillBaneOfMonstersScenarioTest : FunSpec({

    fun testArtifact(): CardDefinition = CardDefinition(
        name = "Chevill Target Artifact",
        manaCost = ManaCost.ZERO,
        typeLine = TypeLine.parse("Artifact"),
    )

    fun testPlaneswalker(): CardDefinition = CardDefinition(
        name = "Chevill Target Planeswalker",
        manaCost = ManaCost.ZERO,
        typeLine = TypeLine.parse("Planeswalker — Test"),
        startingLoyalty = 3,
    )

    fun bountyWipe(): CardDefinition = CardDefinition.sorcery(
        name = "Chevill Simultaneous Creature Wipe",
        manaCost = ManaCost.parse("{3}{W}{W}"),
        oracleText = "Destroy all creatures your opponents control.",
        script = CardScript.spell(
            effect = Effects.DestroyAll(
                filter = GameObjectFilter.Creature.opponentControls(),
                noRegenerate = true,
            ),
        ),
    )

    fun newDriver(extraCards: List<CardDefinition> = emptyList()): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + extraCards)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40))
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun GameTestDriver.bountyCount(entityId: EntityId): Int =
        state.getEntity(entityId)?.get<CountersComponent>()?.getCount(CounterType.BOUNTY) ?: 0

    fun GameTestDriver.putBounty(entityId: EntityId) {
        addComponent(
            entityId,
            CountersComponent().withAdded(CounterType.BOUNTY, 1),
        )
    }

    fun advanceToChevillControllerUpkeep(driver: GameTestDriver) {
        // The driver starts in Player 1's precombat main phase. Walk through Player 1's end,
        // Player 2's upkeep/end, and arrive at Player 1's next upkeep.
        driver.passPriorityUntil(Step.END)
        driver.passPriorityUntil(Step.UPKEEP)
        driver.passPriorityUntil(Step.END)
        driver.passPriorityUntil(Step.UPKEEP)
    }

    fun putChevillAndTarget(driver: GameTestDriver): Pair<EntityId, EntityId> {
        val chevill = driver.putCreatureOnBattlefield(driver.player1, "Chevill, Bane of Monsters")
        val target = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        return chevill to target
    }

    fun destroyWithDoomBlade(driver: GameTestDriver, target: EntityId) {
        val doomBlade = driver.putCardInHand(driver.player1, "Doom Blade")
        driver.giveMana(driver.player1, com.wingedsheep.sdk.core.Color.BLACK, 2)
        driver.castSpell(driver.player1, doomBlade, targets = listOf(target)).isSuccess shouldBe true
        driver.bothPass()
    }

    test("CHEVILL-01: upkeep trigger chooses an opposing creature and adds one BOUNTY") {
        val driver = newDriver()
        putChevillAndTarget(driver)

        advanceToChevillControllerUpkeep(driver)

        val decision = driver.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        decision.legalTargets.getValue(0) shouldContain driver.findPermanent(driver.player2, "Grizzly Bears")
        val target = driver.findPermanent(driver.player2, "Grizzly Bears")!!
        driver.submitTargetSelection(driver.player1, listOf(target))
        driver.bothPass()

        driver.bountyCount(target) shouldBe 1
    }

    test("CHEVILL-02: an existing opposing BOUNTY suppresses the upkeep trigger") {
        val driver = newDriver()
        putChevillAndTarget(driver)
        val marked = driver.findPermanent(driver.player2, "Grizzly Bears")!!
        driver.putBounty(marked)

        advanceToChevillControllerUpkeep(driver)

        driver.pendingDecision shouldBe null
        driver.stackSize shouldBe 0
    }

    test("CHEVILL-03: intervening-if is checked again when the upkeep trigger resolves") {
        val driver = newDriver()
        putChevillAndTarget(driver)
        val secondPermanent = driver.putCreatureOnBattlefield(driver.player2, "Hill Giant")

        advanceToChevillControllerUpkeep(driver)

        val target = driver.findPermanent(driver.player2, "Grizzly Bears")!!
        driver.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        driver.submitTargetSelection(driver.player1, listOf(target))
        driver.putBounty(secondPermanent)
        driver.bothPass()

        driver.bountyCount(target) shouldBe 0
        driver.bountyCount(secondPermanent) shouldBe 1
    }

    test("CHEVILL-04: false intervening-if wins before an illegal target at resolution") {
        val driver = newDriver()
        putChevillAndTarget(driver)
        val conditionMarker = driver.putCreatureOnBattlefield(driver.player2, "Hill Giant")

        advanceToChevillControllerUpkeep(driver)

        val target = driver.findPermanent(driver.player2, "Grizzly Bears")!!
        driver.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        driver.submitTargetSelection(driver.player1, listOf(target))
        driver.putBounty(conditionMarker)
        driver.moveToGraveyard(target)
        driver.bothPass()

        driver.events.filterIsInstance<AbilityFizzledEvent>().last().reason shouldBe
            "Intervening-if condition is no longer true"
        driver.bountyCount(target) shouldBe 0
    }

    test("CHEVILL-05: a marked opposing permanent dying gains 3 life and draws one card") {
        val driver = newDriver()
        putChevillAndTarget(driver)
        val target = driver.findPermanent(driver.player2, "Grizzly Bears")!!
        driver.putBounty(target)
        val lifeBefore = driver.getLifeTotal(driver.player1)
        val handBefore = driver.getHandSize(driver.player1)

        destroyWithDoomBlade(driver, target)
        driver.stackSize shouldBe 1
        driver.bothPass()

        driver.getLifeTotal(driver.player1) shouldBe lifeBefore + 3
        driver.getHandSize(driver.player1) shouldBe handBefore + 1
    }

    test("CHEVILL-06: removing BOUNTY before death suppresses the payoff trigger") {
        val driver = newDriver()
        putChevillAndTarget(driver)
        val target = driver.findPermanent(driver.player2, "Grizzly Bears")!!
        driver.putBounty(target)
        driver.addComponent(
            target,
            CountersComponent(mapOf(CounterType.PLUS_ONE_PLUS_ONE to 1)),
        )
        val lifeBefore = driver.getLifeTotal(driver.player1)
        val handBefore = driver.getHandSize(driver.player1)

        destroyWithDoomBlade(driver, target)

        driver.stackSize shouldBe 0
        driver.getLifeTotal(driver.player1) shouldBe lifeBefore
        driver.getHandSize(driver.player1) shouldBe handBefore
    }

    test("CHEVILL-07: the death trigger reads BOUNTY from the physical object's LKI snapshot") {
        val driver = newDriver()
        putChevillAndTarget(driver)
        val target = driver.findPermanent(driver.player2, "Grizzly Bears")!!
        driver.putBounty(target)
        val lifeBefore = driver.getLifeTotal(driver.player1)

        destroyWithDoomBlade(driver, target)

        val death = driver.events
            .filterIsInstance<ZoneChangeEvent>()
            .last { it.entityId == target && it.fromZone == Zone.BATTLEFIELD }
        death.lastKnown.shouldNotBeNull().counters[Counters.BOUNTY] shouldBe 1
        driver.state.getZone(com.wingedsheep.engine.state.ZoneKey(driver.player2, Zone.BATTLEFIELD))
            .contains(target) shouldBe false

        driver.bothPass()
        driver.getLifeTotal(driver.player1) shouldBe lifeBefore + 3
    }

    test("CHEVILL-08: simultaneous marked and unmarked deaths produce only qualifying payoffs") {
        val wipe = bountyWipe()
        val driver = newDriver(listOf(wipe))
        putChevillAndTarget(driver)
        val markedOne = driver.findPermanent(driver.player2, "Grizzly Bears")!!
        val markedTwo = driver.putCreatureOnBattlefield(driver.player2, "Hill Giant")
        val unmarked = driver.putCreatureOnBattlefield(driver.player2, "Centaur Courser")
        driver.putBounty(markedOne)
        driver.putBounty(markedTwo)
        val lifeBefore = driver.getLifeTotal(driver.player1)
        val handBefore = driver.getHandSize(driver.player1)

        val wipeInHand = driver.putCardInHand(driver.player1, wipe.name)
        driver.giveMana(driver.player1, com.wingedsheep.sdk.core.Color.WHITE, 5)
        driver.castSpell(driver.player1, wipeInHand).isSuccess shouldBe true
        driver.bothPass()

        driver.stackSize shouldBe 2
        driver.bothPass()
        driver.bothPass()

        driver.getLifeTotal(driver.player1) shouldBe lifeBefore + 6
        driver.getHandSize(driver.player1) shouldBe handBefore + 2
        driver.state.getZone(com.wingedsheep.engine.state.ZoneKey(driver.player2, Zone.BATTLEFIELD))
            .contains(unmarked) shouldBe false
    }

    test("CHEVILL-09: leaving the battlefield after the death trigger is created does not invalidate it") {
        val driver = newDriver()
        val (chevill, target) = putChevillAndTarget(driver)
        driver.putBounty(target)
        val lifeBefore = driver.getLifeTotal(driver.player1)

        destroyWithDoomBlade(driver, target)
        driver.stackSize shouldBe 1
        driver.moveToGraveyard(chevill)
        driver.bothPass()

        driver.getLifeTotal(driver.player1) shouldBe lifeBefore + 3
    }

    test("CHEVILL-10: upkeep targeting allows opposing creatures and planeswalkers only") {
        val artifact = testArtifact()
        val planeswalker = testPlaneswalker()
        val driver = newDriver(listOf(artifact, planeswalker))
        putChevillAndTarget(driver)
        val ownCreature = driver.putCreatureOnBattlefield(driver.player1, "Hill Giant")
        val opposingCreature = driver.findPermanent(driver.player2, "Grizzly Bears")!!
        val opposingPlaneswalker = driver.putPermanentOnBattlefield(
            driver.player2,
            planeswalker.name,
        )
        // putPermanentOnBattlefield is a direct-placement helper; seed the loyalty counters
        // that a normal planeswalker entry would receive before the next SBA check.
        driver.addComponent(
            opposingPlaneswalker,
            CountersComponent(mapOf(CounterType.LOYALTY to 3)),
        )
        val opposingArtifact = driver.putPermanentOnBattlefield(driver.player2, artifact.name)
        val nonPermanent = driver.putCardInHand(driver.player2, "Doom Blade")

        advanceToChevillControllerUpkeep(driver)

        val legalTargets = driver.pendingDecision
            .shouldBeInstanceOf<ChooseTargetsDecision>()
            .legalTargets.getValue(0)
        legalTargets shouldContain opposingCreature
        legalTargets shouldContain opposingPlaneswalker
        legalTargets shouldNotContain ownCreature
        legalTargets shouldNotContain opposingArtifact
        legalTargets shouldNotContain nonPermanent
    }

    test("CHEVILL-11: the CardDefinition reuses the normal deathtouch keyword") {
        val driver = newDriver()

        driver.cardRegistry.requireCard("Chevill, Bane of Monsters").keywords
            .contains(com.wingedsheep.sdk.core.Keyword.DEATHTOUCH) shouldBe true
    }

    test("CHEVILL-12: the registry definition round-trips through CardLoader serialization") {
        val driver = newDriver()
        val definition = driver.cardRegistry.requireCard("Chevill, Bane of Monsters")
        val encoded = CardLoader.toJson(definition)
        val decoded = CardLoader.fromJson(encoded)

        encoded shouldContain "Chevill, Bane of Monsters"
        decoded.name shouldBe definition.name
        decoded.oracleText shouldBe definition.oracleText
        decoded.keywords shouldBe definition.keywords
        decoded.script.triggeredAbilities.size shouldBe 2
    }
})
