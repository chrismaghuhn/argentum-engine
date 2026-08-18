package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.mechanics.cost.CostAmountResolver
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.DynamicAmounts
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/** Regression coverage for dynamic PayLife across every cost-entry path. */
class DynamicPayLifePathRegressionTest : FunSpec({

    val zoneAbilityCard = card("Test Dynamic Zone Life Ability") {
        typeLine = "Creature — Human"
        power = 1
        toughness = 1
        activatedAbility {
            cost = Costs.PayLife(DynamicAmounts.commanderColorIdentityCount())
            effect = Effects.DrawCards(1)
            activateFromZone = Zone.GRAVEYARD
        }
    }

    val manaAbilityCard = card("Test Dynamic Mana Life Ability") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Composite(
                Costs.Tap,
                Costs.PayLife(DynamicAmounts.commanderColorIdentityCount())
            )
            effect = Effects.AddColorlessMana(1)
            manaAbility = true
        }
    }

    val additionalLifeSpell = card("Test Dynamic Additional Life Spell") {
        manaCost = "{0}"
        typeLine = "Sorcery"
        spell { effect = Effects.DrawCards(1) }
        additionalCost(Costs.additional.PayLife(DynamicAmounts.commanderColorIdentityCount()))
    }

    val negativeLifePermanent = card("Test Negative Dynamic Life Cost Permanent") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.PayLife(
                DynamicAmount.Subtract(
                    DynamicAmount.Fixed(0),
                    DynamicAmounts.commanderColorIdentityCount()
                )
            )
            effect = Effects.DrawCards(1)
        }
    }

    val autoTapSpell = card("Test Dynamic Mana Auto Tap Spell") {
        manaCost = "{1}"
        typeLine = "Sorcery"
        spell { effect = Effects.DrawCards(1) }
    }

    val monoCommander = card("Test Dynamic PayLife Mono Commander") {
        manaCost = "{G}"
        colorIdentity = "G"
        typeLine = "Legendary Creature — Human"
        power = 1
        toughness = 1
    }

    val colorlessCommander = card("Test Dynamic PayLife Colorless Commander") {
        manaCost = "{5}"
        colorIdentity = ""
        typeLine = "Legendary Creature — Golem"
        power = 5
        toughness = 5
    }

    fun createDriver(commanderName: String = monoCommander.name): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(
            TestCards.all + listOf(
                zoneAbilityCard,
                manaAbilityCard,
                additionalLifeSpell,
                negativeLifePermanent,
                autoTapSpell,
                monoCommander,
                colorlessCommander
            )
        )
        driver.initMultiplayer(
            decks = listOf(Deck.of("Forest" to 40), Deck.of("Forest" to 40)),
            format = Format.Commander(),
            commanders = listOf(commanderName, commanderName)
        )
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun activatedAction(driver: GameTestDriver, sourceId: com.wingedsheep.sdk.model.EntityId) =
        driver.legalActions(driver.activePlayer!!).firstOrNull { legalAction ->
            (legalAction.action as? ActivateAbility)?.sourceId == sourceId
        }

    fun castAction(driver: GameTestDriver, cardId: com.wingedsheep.sdk.model.EntityId) =
        driver.legalActions(driver.activePlayer!!).firstOrNull { legalAction ->
            (legalAction.action as? CastSpell)?.cardId == cardId
        }

    test("commander color identity composes through arithmetic in the cost resolver") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, negativeLifePermanent.name)
        val amount = DynamicAmount.Add(
            DynamicAmounts.commanderColorIdentityCount(),
            DynamicAmount.Fixed(1)
        )

        CostAmountResolver.resolve(
            state = driver.state,
            amount = amount,
            sourceId = sourceId,
            controllerId = player,
            cardRegistry = driver.cardRegistry
        ) shouldBe 2
    }

    test("zone activation enumeration rejects an unaffordable dynamic life cost") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        driver.setLifeTotal(player, 0)
        val sourceId = driver.putCardInGraveyard(player, zoneAbilityCard.name)

        activatedAction(driver, sourceId) shouldBe null
    }

    test("colorless commander resolves dynamic life to zero and remains payable at zero life") {
        val driver = createDriver(colorlessCommander.name)
        val player = driver.activePlayer!!
        driver.setLifeTotal(player, 0)
        val sourceId = driver.putCardInGraveyard(player, zoneAbilityCard.name)

        activatedAction(driver, sourceId).shouldNotBeNull()
    }

    test("mana activation enumeration rejects an unaffordable dynamic life cost") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        driver.setLifeTotal(player, 0)
        val sourceId = driver.putPermanentOnBattlefield(player, manaAbilityCard.name)

        val action = activatedAction(driver, sourceId).shouldNotBeNull()
        action.affordable shouldBe false
        driver.submit(action.action).error shouldBe "Cannot pay ability cost"
    }

    test("spell additional-cost enumeration rejects an unaffordable dynamic life cost") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        driver.setLifeTotal(player, 0)
        val spellId = driver.putCardInHand(player, additionalLifeSpell.name)

        castAction(driver, spellId) shouldBe null
    }

    test("auto-tap deducts the resolved dynamic life cost of a mana ability") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        driver.setLifeTotal(player, 2)
        driver.putPermanentOnBattlefield(player, manaAbilityCard.name)
        val spellId = driver.putCardInHand(player, autoTapSpell.name)
        val cast = castAction(driver, spellId).shouldNotBeNull()

        driver.submit(cast.action).error shouldBe null
        driver.getLifeTotal(player) shouldBe 1
    }

    test("auto-tap does not use a dynamic life-cost source the player cannot afford") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        driver.setLifeTotal(player, 0)
        driver.putPermanentOnBattlefield(player, manaAbilityCard.name)
        val spellId = driver.putCardInHand(player, autoTapSpell.name)

        castAction(driver, spellId) shouldBe null
    }

    test("negative dynamic life costs are not legal") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, negativeLifePermanent.name)
        val abilityId = driver.cardRegistry.getCard(negativeLifePermanent.name)!!.activatedAbilities.single().id

        activatedAction(driver, sourceId) shouldBe null
        driver.submit(ActivateAbility(player, sourceId, abilityId)).error.shouldNotBeNull()
    }
})
