package com.wingedsheep.engine.mana

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.mechanics.mana.SpellPaymentContext
import com.wingedsheep.engine.mechanics.mana.toManaPool
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.rav.cards.GolgariSignet
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardScript
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.AbilityCost
import com.wingedsheep.sdk.scripting.TimingRule
import com.wingedsheep.sdk.scripting.effects.AddManaEffect
import com.wingedsheep.sdk.scripting.effects.ManaRestriction
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/** Regression coverage for paid mana-source reachability in [ManaSolver]. */
class ManaSolverSelfFundingTest : FunSpec({

    val creatureOnlyPrerequisite = card("Creature-Only Prerequisite Source") {
        typeLine = "Land"

        activatedAbility {
            cost = AbilityCost.Tap
            effect = AddManaEffect(Color.GREEN, restriction = ManaRestriction.CreatureSpellsOnly)
            manaAbility = true
            timing = TimingRule.ManaAbility
        }
    }

    val abilityOnlyPrerequisite = card("Ability-Only Prerequisite Source") {
        typeLine = "Land"

        activatedAbility {
            cost = AbilityCost.Tap
            effect = AddManaEffect(Color.GREEN, restriction = ManaRestriction.AbilityActivationOnly)
            manaAbility = true
            timing = TimingRule.ManaAbility
        }
    }

    val paidColorlessSource = card("Paid Colorless Source") {
        typeLine = "Land"

        activatedAbility {
            cost = Costs.Composite(Costs.Mana("{1}"), Costs.Tap)
            effect = Effects.AddColorlessMana(2)
            manaAbility = true
            timing = TimingRule.ManaAbility
        }
    }

    val selfFundingInstant = CardDefinition.instant(
        name = "Self-Funding Green Instant",
        manaCost = ManaCost.parse("{G}"),
        oracleText = "",
        script = CardScript.EMPTY,
    )

    val selfFundingGenericInstant = CardDefinition.instant(
        name = "Self-Funding Generic Green Instant",
        manaCost = ManaCost.parse("{1}{G}"),
        oracleText = "",
        script = CardScript.EMPTY,
    )

    val selfFundingActivatedAbility = card("Self-Funding Activated Ability") {
        typeLine = "Artifact"

        activatedAbility {
            cost = Costs.Mana("{G}")
            effect = Effects.GainLife(1)
        }
    }

    fun createDriver(extraCards: List<CardDefinition> = emptyList()): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + GolgariSignet + extraCards)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), skipMulligans = true)
        return driver
    }

    test("MANA-SOLVER-SELF-FUNDING-01 rejects a signet as its own activation payment") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        driver.putPermanentOnBattlefield(player, "Golgari Signet")

        val solver = ManaSolver(driver.cardRegistry)
        val cost = ManaCost.parse("{G}")

        solver.solve(driver.state, player, cost) shouldBe null
    }

    test("MANA-SOLVER-SELF-FUNDING-01 canPay rejects a signet as its own activation payment") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        driver.putPermanentOnBattlefield(player, "Golgari Signet")

        ManaSolver(driver.cardRegistry).canPay(
            driver.state,
            player,
            ManaCost.parse("{G}"),
        ) shouldBe false
    }

    test("an independent Forest funds the Signet before its output pays the outer cost") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val forest = driver.putLandOnBattlefield(player, "Forest")
        val signet = driver.putPermanentOnBattlefield(player, "Golgari Signet")

        val solution = ManaSolver(driver.cardRegistry).solve(
            driver.state,
            player,
            ManaCost.parse("{B}{G}"),
        )

        val nonNullSolution = solution.shouldNotBeNull()
        nonNullSolution.sources.map { it.entityId } shouldContainExactly listOf(forest, signet)
        nonNullSolution.manaProduced.keys shouldContainExactly listOf(signet)
    }

    test("an initial Forest can seed a chain of two paid sources") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val forest = driver.putLandOnBattlefield(player, "Forest")
        val firstSignet = driver.putPermanentOnBattlefield(player, "Golgari Signet")
        val secondSignet = driver.putPermanentOnBattlefield(player, "Golgari Signet")

        val solution = ManaSolver(driver.cardRegistry).solve(
            driver.state,
            player,
            ManaCost.parse("{B}{B}"),
        )

        val nonNullSolution = solution.shouldNotBeNull()
        nonNullSolution.sources.map { it.entityId } shouldContainExactly listOf(forest, firstSignet, secondSignet)
        nonNullSolution.manaProduced.keys.toList() shouldContainExactly listOf(firstSignet, secondSignet)
    }

    test("two paid mana sources cannot bootstrap one another from an empty pool") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        driver.putPermanentOnBattlefield(player, "Golgari Signet")
        driver.putPermanentOnBattlefield(player, "Golgari Signet")

        val solver = ManaSolver(driver.cardRegistry)
        val cost = ManaCost.parse("{B}{B}")

        solver.canPay(driver.state, player, cost) shouldBe false
        solver.solve(driver.state, player, cost) shouldBe null
    }

    test("SELF-FUND-06 existing colorless floating mana can seed Signet activation") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        driver.giveColorlessMana(player, 1)
        driver.putPermanentOnBattlefield(player, "Golgari Signet")

        ManaSolver(driver.cardRegistry).canPay(
            driver.state,
            player,
            ManaCost.parse("{G}"),
        ) shouldBe true
    }

    test("SELF-FUND-06 shared pool ledger reserves colorless mana for Signet activation") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        driver.giveColorlessMana(player, 1)
        val signet = driver.putPermanentOnBattlefield(player, "Golgari Signet")
        val pool = driver.state.getEntity(player)!!
            .get<com.wingedsheep.engine.state.components.player.ManaPoolComponent>()!!
            .toManaPool()

        val solution = ManaSolver(driver.cardRegistry).solve(
            driver.state,
            player,
            ManaCost.parse("{G}"),
            initialManaPool = pool,
        ).shouldNotBeNull()

        solution.sources.map { it.entityId } shouldContainExactly listOf(signet)
        solution.poolAfterPayment!!.colorless shouldBe 0
        solution.poolManaSpentForActivation.colorless shouldBe 1
        solution.poolManaSpentForOuter.total shouldBe 0
    }

    test("SELF-FUND-06 reserves floating mana for Signet before the outer generic cost") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        driver.giveColorlessMana(player, 1)
        driver.putPermanentOnBattlefield(player, "Golgari Signet")

        ManaSolver(driver.cardRegistry).canPay(
            driver.state,
            player,
            ManaCost.parse("{1}{G}"),
        ) shouldBe true
    }

    test("SELF-FUND-06 AutoPay spends existing pool mana on Signet activation") {
        val driver = createDriver(listOf(selfFundingInstant))
        val player = driver.activePlayer!!
        driver.giveColorlessMana(player, 1)
        val signet = driver.putPermanentOnBattlefield(player, "Golgari Signet")
        val spell = driver.putCardInHand(player, selfFundingInstant.name)

        driver.submitSuccess(
            CastSpell(
                playerId = player,
                cardId = spell,
                paymentStrategy = PaymentStrategy.AutoPay,
            )
        )

        driver.state.getEntity(signet)?.has<com.wingedsheep.engine.state.components.battlefield.TappedComponent>() shouldBe true
        driver.state.getEntity(player)?.get<com.wingedsheep.engine.state.components.player.ManaPoolComponent>()?.colorless shouldBe 0
    }

    test("SELF-FUND-06 AutoPay reserves existing pool mana for Signet before an outer generic pip") {
        val driver = createDriver(listOf(selfFundingGenericInstant))
        val player = driver.activePlayer!!
        driver.giveColorlessMana(player, 1)
        val signet = driver.putPermanentOnBattlefield(player, "Golgari Signet")
        val spell = driver.putCardInHand(player, selfFundingGenericInstant.name)

        driver.submitSuccess(
            CastSpell(
                playerId = player,
                cardId = spell,
                paymentStrategy = PaymentStrategy.AutoPay,
            )
        )

        driver.state.getEntity(signet)?.has<com.wingedsheep.engine.state.components.battlefield.TappedComponent>() shouldBe true
        driver.state.getEntity(player)?.get<com.wingedsheep.engine.state.components.player.ManaPoolComponent>()?.colorless shouldBe 0
    }

    test("SELF-FUND-06 AutoPay spends existing pool mana on a paid activated ability") {
        val driver = createDriver(listOf(selfFundingActivatedAbility))
        val player = driver.activePlayer!!
        driver.giveColorlessMana(player, 1)
        val signet = driver.putPermanentOnBattlefield(player, "Golgari Signet")
        val consumer = driver.putPermanentOnBattlefield(player, selfFundingActivatedAbility.name)
        val abilityId = selfFundingActivatedAbility.script.activatedAbilities.single().id
        val lifeBefore = driver.getLifeTotal(player)

        driver.submitSuccess(
            ActivateAbility(
                playerId = player,
                sourceId = consumer,
                abilityId = abilityId,
                paymentStrategy = PaymentStrategy.AutoPay,
            )
        )
        driver.bothPass()

        driver.getLifeTotal(player) shouldBe lifeBefore + 1
        driver.state.getEntity(signet)?.has<com.wingedsheep.engine.state.components.battlefield.TappedComponent>() shouldBe true
        driver.state.getEntity(player)?.get<com.wingedsheep.engine.state.components.player.ManaPoolComponent>()?.colorless shouldBe 0
    }

    test("SELF-FUND-08 AutoPay consumes restricted pool mana used for nested activation") {
        val driver = createDriver(listOf(selfFundingActivatedAbility))
        val player = driver.activePlayer!!
        driver.giveRestrictedMana(
            player,
            color = null,
            amount = 1,
            restriction = ManaRestriction.AbilityActivationOnly,
        )
        val signet = driver.putPermanentOnBattlefield(player, "Golgari Signet")
        val consumer = driver.putPermanentOnBattlefield(player, selfFundingActivatedAbility.name)
        val abilityId = selfFundingActivatedAbility.script.activatedAbilities.single().id

        driver.submitSuccess(
            ActivateAbility(
                playerId = player,
                sourceId = consumer,
                abilityId = abilityId,
                paymentStrategy = PaymentStrategy.AutoPay,
            )
        )
        driver.bothPass()

        val pool = driver.state.getEntity(player)
            ?.get<com.wingedsheep.engine.state.components.player.ManaPoolComponent>()
        pool?.restrictedMana shouldBe emptyList()
        pool?.colorless shouldBe 0
        driver.state.getEntity(signet)?.has<com.wingedsheep.engine.state.components.battlefield.TappedComponent>() shouldBe true
    }

    test("SELF-FUND-08 solver exposes the pool after restricted nested activation spend") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        driver.giveRestrictedMana(
            player,
            color = null,
            amount = 1,
            restriction = ManaRestriction.AbilityActivationOnly,
        )
        driver.putPermanentOnBattlefield(player, "Golgari Signet")
        val pool = driver.state.getEntity(player)!!
            .get<com.wingedsheep.engine.state.components.player.ManaPoolComponent>()!!
            .toManaPool()

        val solution = ManaSolver(driver.cardRegistry).solve(
            driver.state,
            player,
            ManaCost.parse("{G}"),
            spellContext = SpellPaymentContext(isAbilityActivation = true),
            initialManaPool = pool,
        ).shouldNotBeNull()

        solution.poolAfterActivation!!.restrictedMana shouldBe emptyList()
        solution.poolAfterPayment!!.restrictedMana shouldBe emptyList()
    }

    test("SELF-FUND-07 spell-only prerequisite mana cannot pay a Signet activation") {
        val driver = createDriver(listOf(creatureOnlyPrerequisite))
        val player = driver.activePlayer!!
        driver.putPermanentOnBattlefield(player, creatureOnlyPrerequisite.name)
        driver.putPermanentOnBattlefield(player, "Golgari Signet")

        val creatureSpellContext = SpellPaymentContext(
            isCreature = true,
            cardTypes = setOf(CardType.CREATURE),
        )

        val solver = ManaSolver(driver.cardRegistry)
        solver.solve(
            driver.state,
            player,
            ManaCost.parse("{B}"),
            spellContext = creatureSpellContext,
        ) shouldBe null
        solver.canPay(
            driver.state,
            player,
            ManaCost.parse("{B}"),
            spellContext = creatureSpellContext,
        ) shouldBe false
    }

    test("SELF-FUND-07 spell-only restricted pool mana cannot pay a Signet activation") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        driver.giveRestrictedMana(
            player,
            color = null,
            amount = 1,
            restriction = ManaRestriction.CreatureSpellsOnly,
        )
        driver.putPermanentOnBattlefield(player, "Golgari Signet")
        val pool = driver.state.getEntity(player)!!
            .get<com.wingedsheep.engine.state.components.player.ManaPoolComponent>()!!
            .toManaPool()
        val creatureSpellContext = SpellPaymentContext(
            isCreature = true,
            cardTypes = setOf(CardType.CREATURE),
        )

        ManaSolver(driver.cardRegistry).solve(
            driver.state,
            player,
            ManaCost.parse("{B}"),
            spellContext = creatureSpellContext,
            initialManaPool = pool,
        ) shouldBe null
    }

    test("SELF-FUND-08 ability-only prerequisite mana can pay a Signet activation") {
        val driver = createDriver(listOf(abilityOnlyPrerequisite))
        val player = driver.activePlayer!!
        val prerequisite = driver.putPermanentOnBattlefield(player, abilityOnlyPrerequisite.name)
        val signet = driver.putPermanentOnBattlefield(player, "Golgari Signet")

        val creatureSpellContext = SpellPaymentContext(
            isCreature = true,
            cardTypes = setOf(CardType.CREATURE),
        )

        val solver = ManaSolver(driver.cardRegistry)
        val solution = solver.solve(
            driver.state,
            player,
            ManaCost.parse("{B}"),
            spellContext = creatureSpellContext,
        ).shouldNotBeNull()
        solution.sources.map { it.entityId } shouldContainExactly listOf(prerequisite, signet)
        solver.canPay(
            driver.state,
            player,
            ManaCost.parse("{B}"),
            spellContext = creatureSpellContext,
        ) shouldBe true
    }

    test("paid colorless production cannot self-fund its activation cost") {
        val driver = createDriver(listOf(paidColorlessSource))
        val player = driver.activePlayer!!
        driver.putPermanentOnBattlefield(player, paidColorlessSource.name)

        ManaSolver(driver.cardRegistry).canPay(
            driver.state,
            player,
            ManaCost.parse("{C}{C}"),
        ) shouldBe false
    }

    test("paid colorless production can use an independent Forest to pay its activation") {
        val driver = createDriver(listOf(paidColorlessSource))
        val player = driver.activePlayer!!
        val forest = driver.putLandOnBattlefield(player, "Forest")
        val source = driver.putPermanentOnBattlefield(player, paidColorlessSource.name)

        val solved = ManaSolver(driver.cardRegistry).solve(
            driver.state,
            player,
            ManaCost.parse("{C}"),
        ).shouldNotBeNull()

        solved.sources.map { it.entityId } shouldContainExactly listOf(forest, source)
        solved.manaProduced.keys shouldContainExactly listOf(source)
        solved.sources.single { it.entityId == source }.colorlessActivationManaCost shouldBe 1
    }

    test("paid colorless production can use existing colorless floating mana") {
        val driver = createDriver(listOf(paidColorlessSource))
        val player = driver.activePlayer!!
        driver.giveColorlessMana(player, 1)
        val source = driver.putPermanentOnBattlefield(player, paidColorlessSource.name)
        val pool = driver.state.getEntity(player)!!
            .get<com.wingedsheep.engine.state.components.player.ManaPoolComponent>()!!
            .toManaPool()

        val solved = ManaSolver(driver.cardRegistry).solve(
            driver.state,
            player,
            ManaCost.parse("{C}{C}"),
            initialManaPool = pool,
        ).shouldNotBeNull()

        solved.sources.map { it.entityId } shouldContainExactly listOf(source)
        solved.poolManaSpentForActivation.colorless shouldBe 1
        solved.manaProduced[source]!!.colorless shouldBe 2
    }
})
