package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.DecisionPhase
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.SelectManaSourcesDecision
import com.wingedsheep.engine.handlers.CostHandler
import com.wingedsheep.engine.mechanics.mana.ManaPaymentWindow
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.rav.cards.GolgariSignet
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.AbilityCost
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Characterizations for #106. This file targets the rules seams and keeps the two CR timing
 * equivalence results as permanent guardrails; V5 qualification is covered by the Gym contract
 * tests.
 */
class PaidManaSourcePaymentRedTest : FunSpec({

    fun signetDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + GolgariSignet)
        driver.initMirrorMatch(Deck.of("Forest" to 40), startingPlayer = 0)
        driver.passPriorityUntil(com.wingedsheep.sdk.core.Step.PRECOMBAT_MAIN)
        return driver
    }

    fun signetAbilityId(): AbilityId = GolgariSignet.activatedAbilities.single().id

    data class SignetPaymentSnapshot(
        val forestTapped: Boolean,
        val signetTapped: Boolean,
        val black: Int,
        val green: Int,
        val colorless: Int,
    )

    fun snapshot(driver: GameTestDriver, playerId: com.wingedsheep.sdk.model.EntityId, forestId: com.wingedsheep.sdk.model.EntityId, signetId: com.wingedsheep.sdk.model.EntityId): SignetPaymentSnapshot {
        val pool = driver.state.getEntity(playerId)?.get<ManaPoolComponent>()
            ?: error("Player has no mana pool")
        return SignetPaymentSnapshot(
            forestTapped = driver.state.getEntity(forestId)?.has<TappedComponent>() == true,
            signetTapped = driver.state.getEntity(signetId)?.has<TappedComponent>() == true,
            black = pool.black,
            green = pool.green,
            colorless = pool.colorless,
        )
    }

    test("PAY106-ORDER-01: Golgari Signet cost components have equivalent fixed-slice outcomes in either legal order") {
        val driver = signetDriver()
        val player = driver.activePlayer!!
        val signetId = driver.putPermanentOnBattlefield(player, GolgariSignet.name)
        val printedCost = GolgariSignet.activatedAbilities.single().cost
        val reversedCost = (printedCost as AbilityCost.Composite).let { AbilityCost.Composite(it.costs.reversed()) }
        val handler = CostHandler(driver.cardRegistry)

        val forward = handler.payAbilityCost(
            state = driver.state,
            cost = printedCost,
            sourceId = signetId,
            controllerId = player,
            manaPool = ManaPool(green = 1),
        )
        val reverse = handler.payAbilityCost(
            state = driver.state,
            cost = reversedCost,
            sourceId = signetId,
            controllerId = player,
            manaPool = ManaPool(green = 1),
        )

        forward.success shouldBe true
        reverse.success shouldBe true
        forward.newState shouldBe reverse.newState
        forward.newManaPool shouldBe reverse.newManaPool
        forward.events shouldBe reverse.events
    }

    test("PAY106-MANA-WINDOW-01: pre-generation and nested prerequisite mana are equivalent for fixed Golgari Signet") {
        val preGenerated = signetDriver()
        val prePlayer = preGenerated.activePlayer!!
        val preForest = preGenerated.putPermanentOnBattlefield(prePlayer, "Forest")
        val preSignet = preGenerated.putPermanentOnBattlefield(prePlayer, GolgariSignet.name)

        preGenerated.submitSuccess(
            ActivateAbility(
                playerId = prePlayer,
                sourceId = preForest,
                abilityId = AbilityId.intrinsicMana(Color.GREEN.symbol),
            ),
        )
        preGenerated.submitSuccess(
            ActivateAbility(
                playerId = prePlayer,
                sourceId = preSignet,
                abilityId = signetAbilityId(),
                paymentStrategy = PaymentStrategy.FromPool,
            ),
        )
        val preSnapshot = snapshot(preGenerated, prePlayer, preForest, preSignet)

        val nested = signetDriver()
        val nestedPlayer = nested.activePlayer!!
        val nestedForest = nested.putPermanentOnBattlefield(nestedPlayer, "Forest")
        val nestedSignet = nested.putPermanentOnBattlefield(nestedPlayer, GolgariSignet.name)
        val window = ManaPaymentWindow.buildDecision(
            state = nested.state,
            playerId = nestedPlayer,
            cost = ManaCost.parse("{1}"),
            decisionId = "pay106-mana-window",
            prompt = "Pay {1}",
            context = DecisionContext(
                sourceId = nestedSignet,
                sourceName = "PAY106 outer payment",
                phase = DecisionPhase.CASTING,
            ),
            canDecline = false,
            cardRegistry = nested.cardRegistry,
        )
        window.availableSources.any { it.entityId == nestedForest } shouldBe true
        nested.replaceState(nested.state.withPendingDecision(window))

        val nestedResult = nested.submit(
            ActivateAbility(
                playerId = nestedPlayer,
                sourceId = nestedSignet,
                abilityId = signetAbilityId(),
            ),
        )

        nestedResult.error shouldBe null
        nestedResult.isPaused shouldBe true
        nestedResult.pendingDecision.shouldBeInstanceOf<SelectManaSourcesDecision>()
        snapshot(nested, nestedPlayer, nestedForest, nestedSignet) shouldBe preSnapshot
    }

    val collidingManaArtifact = card("PAY106 Colliding Mana Artifact") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(Color.GREEN)
            manaAbility = true
        }
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(Color.GREEN)
            manaAbility = true
        }
    }

    test("PAY106-KEY-01: structurally colliding legal mana abilities make the source unsupported") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + collidingManaArtifact)
        driver.initMirrorMatch(Deck.of("Forest" to 40), startingPlayer = 0)
        driver.passPriorityUntil(com.wingedsheep.sdk.core.Step.PRECOMBAT_MAIN)
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, collidingManaArtifact.name)
        val source = ManaSolver(driver.cardRegistry)
            .findAvailableManaSources(driver.state, player)
            .single { it.entityId == sourceId }

        val options = source.manaAbilityOptionsFor(Color.GREEN)
        val keys = options.map(ManaAbilityIdentity::key)
        options.size shouldBe 2
        keys.distinct().size shouldBe 1

        // V4/V1 is historical and still accepts this source. V5 qualification must add a
        // per-source uniqueness gate before publishing a candidate domain; the V5-owned
        // characterization of that gate lives in PaymentDomainV5ContractTest.
        source.supportsPaymentPlanV1() shouldBe true
        keys.distinct().size shouldBe 1
    }
})
