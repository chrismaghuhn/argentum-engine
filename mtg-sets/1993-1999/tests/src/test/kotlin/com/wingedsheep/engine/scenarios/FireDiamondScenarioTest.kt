package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.mtg.sets.definitions.mir.cards.FireDiamond
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Fire Diamond (MIR #302) — This artifact enters tapped. {T}: Add {R}.
 */
class FireDiamondScenarioTest : FunSpec({

    val abilityId = FireDiamond.activatedAbilities.single().id

    test("matches current MIR Scryfall Oracle and canonical metadata") {
        FireDiamond.manaCost.toString() shouldBe "{2}"
        FireDiamond.typeLine.toString() shouldBe "Artifact"
        FireDiamond.oracleText shouldBe "This artifact enters tapped.\n{T}: Add {R}."
        FireDiamond.metadata.collectorNumber shouldBe "302"
        FireDiamond.metadata.artist shouldBe "Richard Thomas"
        FireDiamond.metadata.imageUri shouldBe
            "https://cards.scryfall.io/normal/front/b/c/bcca5bbe-df01-45ea-a6ac-4e3d1cf237c8.jpg?1783947042"
    }

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + FireDiamond)
        driver.initMirrorMatch(deck = Deck.of("Mountain" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("Fire Diamond enters tapped and produces red mana after it is untapped") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val diamondInHand = driver.putCardInHand(player, "Fire Diamond")
        driver.giveMana(player, Color.RED, 2)

        driver.castSpell(player, diamondInHand).isSuccess shouldBe true
        driver.bothPass()

        val diamond = driver.findPermanent(player, "Fire Diamond")
        diamond shouldNotBe null
        driver.isTapped(diamond!!) shouldBe true

        driver.untapPermanent(diamond)
        driver.submit(
            ActivateAbility(playerId = player, sourceId = diamond, abilityId = abilityId)
        ).isSuccess shouldBe true

        driver.isTapped(diamond) shouldBe true

        val manaPool = driver.state.getEntity(player)?.get<ManaPoolComponent>()!!
        manaPool.red shouldBe 1
        manaPool.white shouldBe 0
        manaPool.blue shouldBe 0
        manaPool.black shouldBe 0
        manaPool.green shouldBe 0
        manaPool.colorless shouldBe 0
    }

    test("Fire Diamond cannot activate while it is tapped") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val diamond = driver.putPermanentOnBattlefield(player, "Fire Diamond")
        driver.tapPermanent(diamond)

        val result = driver.submit(
            ActivateAbility(playerId = player, sourceId = diamond, abilityId = abilityId)
        )

        result.isSuccess shouldBe false
    }

    test("Fire Diamond cannot be cast without paying its generic mana cost") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val diamondInHand = driver.putCardInHand(player, "Fire Diamond")
        driver.giveMana(player, Color.RED)

        driver.castSpell(player, diamondInHand).isSuccess shouldBe false
    }
})
