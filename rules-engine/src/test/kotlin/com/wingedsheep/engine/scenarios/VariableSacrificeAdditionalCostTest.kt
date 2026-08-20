package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.legalactions.LegalActionEnumerator
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.AdditionalCost
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.ChoiceSlot
import com.wingedsheep.sdk.scripting.costs.CostAtom
import com.wingedsheep.sdk.scripting.costs.PermanentCostAction
import com.wingedsheep.sdk.scripting.GameObjectFilter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/** Characterization tests for the generic optional variable sacrifice cost rail. */
class VariableSacrificeAdditionalCostTest : FunSpec({

    val variableSacrificeProbe = card("Variable Sacrifice Probe") {
        manaCost = "{1}"
        typeLine = "Instant"
        oracleText = "You may sacrifice one or more creatures as an additional cost to cast this spell."
        keywordAbility(
            KeywordAbility.OptionalAdditionalCost(
                additionalCost = AdditionalCost.Atom(
                    CostAtom.VariablePermanents(
                        filter = GameObjectFilter.Creature,
                        minCount = 1,
                        excludeSelf = false,
                        action = PermanentCostAction.SACRIFICE,
                    )
                )
            )
        )
    }

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + variableSacrificeProbe)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), skipMulligans = true)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun castWithVariableSacrifice(
        driver: GameTestDriver,
        player: EntityId,
        spell: EntityId,
        sacrificed: List<EntityId>,
    ) = driver.submit(
        CastSpell(
            playerId = player,
            cardId = spell,
            declaredCostSlot = ChoiceSlot.KICKED,
            additionalCostPayment = AdditionalCostPayment(variableCostPermanents = sacrificed),
            paymentStrategy = PaymentStrategy.AutoPay,
        )
    )

    fun stackSpell(driver: GameTestDriver): SpellOnStackComponent = driver.state.stack
        .mapNotNull { id -> driver.state.getEntity(id)?.get<SpellOnStackComponent>() }
        .single()

    test("optional variable sacrifice publishes its candidates and one-or-more bounds") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val first = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val second = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val spell = driver.putCardInHand(player, variableSacrificeProbe.name)
        driver.putLandOnBattlefield(player, "Plains")

        val action = LegalActionEnumerator.create(driver.cardRegistry)
            .enumerate(driver.state, player)
            .single {
                (it.action as? CastSpell)?.let { cast ->
                    cast.cardId == spell && cast.declaredCostSlot == ChoiceSlot.KICKED
                } == true
            }

        val costInfo = action.additionalCostInfo.shouldNotBeNull()
        costInfo.costType shouldBe "VariableSacrifice"
        costInfo.validSacrificeTargets shouldBe listOf(first, second)
        costInfo.sacrificeMinCount shouldBe 1
        costInfo.sacrificeMaxCount shouldBe 2
    }

    test("declining the optional cost preserves the original cast and sacrifices nothing") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val fodder = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val spell = driver.putCardInHand(player, variableSacrificeProbe.name)
        driver.putLandOnBattlefield(player, "Plains")

        driver.submit(
            CastSpell(playerId = player, cardId = spell, paymentStrategy = PaymentStrategy.AutoPay)
        ).isSuccess shouldBe true

        (fodder in driver.state.getBattlefield()) shouldBe true
        stackSpell(driver).sacrificedPermanents shouldBe emptyList()
    }

    test("one selected creature is paid atomically with exact LKI count") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val fodder = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val spell = driver.putCardInHand(player, variableSacrificeProbe.name)
        driver.putLandOnBattlefield(player, "Plains")

        castWithVariableSacrifice(driver, player, spell, listOf(fodder)).isSuccess shouldBe true

        val stackSpell = stackSpell(driver)
        stackSpell.sacrificedPermanents.map { it.entityId } shouldBe listOf(fodder)
        stackSpell.sacrificedPermanents.size shouldBe 1
        driver.state.getZone(ZoneKey(player, Zone.GRAVEYARD)).toSet() shouldBe setOf(fodder)
    }

    test("multiple selected creatures are paid atomically with exact LKI count") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val normal = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val token = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        driver.replaceState(driver.state.updateEntity(token) { it.with(TokenComponent) })
        val spell = driver.putCardInHand(player, variableSacrificeProbe.name)
        driver.putLandOnBattlefield(player, "Plains")

        castWithVariableSacrifice(driver, player, spell, listOf(normal, token)).isSuccess shouldBe true

        val stackSpell = stackSpell(driver)
        stackSpell.sacrificedPermanents.map { it.entityId } shouldBe listOf(normal, token)
        stackSpell.sacrificedPermanents.size shouldBe 2
        stackSpell.sacrificedPermanents.map { it.name } shouldBe
            listOf("Grizzly Bears", "Grizzly Bears")
        stackSpell.sacrificedPermanents.map { it.wasToken } shouldBe listOf(false, true)
        driver.state.getZone(ZoneKey(player, Zone.GRAVEYARD)).toSet() shouldBe setOf(normal, token)

        // The immutable state fork retains the exact publication used by a replay/copy consumer.
        val fork = driver.state.copy()
        fork.getEntity(fork.stack.single())!!.get<SpellOnStackComponent>()!!.sacrificedPermanents shouldBe
            stackSpell.sacrificedPermanents
    }

    test("insufficient candidates leave the optional action unaffordable and reject payment") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val spell = driver.putCardInHand(player, variableSacrificeProbe.name)
        driver.putLandOnBattlefield(player, "Plains")

        val action = LegalActionEnumerator.create(driver.cardRegistry)
            .enumerate(driver.state, player)
            .single {
                (it.action as? CastSpell)?.let { cast ->
                    cast.cardId == spell && cast.declaredCostSlot == ChoiceSlot.KICKED
                } == true
            }
        action.affordable shouldBe false
        action.additionalCostInfo?.sacrificeMaxCount shouldBe 0

        castWithVariableSacrifice(driver, player, spell, emptyList()).isSuccess shouldBe false
        (spell in driver.state.getZone(ZoneKey(player, Zone.HAND))) shouldBe true
        driver.state.stack.any { it == spell } shouldBe false
    }

    test("duplicate and opponent permanents are rejected without partial payment") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val own = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val theirs = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        val spell = driver.putCardInHand(player, variableSacrificeProbe.name)
        driver.putLandOnBattlefield(player, "Plains")

        castWithVariableSacrifice(driver, player, spell, listOf(own, own)).isSuccess shouldBe false
        castWithVariableSacrifice(driver, player, spell, listOf(theirs)).isSuccess shouldBe false
        (own in driver.state.getBattlefield()) shouldBe true
        (theirs in driver.state.getBattlefield()) shouldBe true
        (spell in driver.state.getZone(ZoneKey(player, Zone.HAND))) shouldBe true
    }

    test("variable sacrifice count and selected ids round-trip through the action serializer") {
        val player = EntityId.generate()
        val spell = EntityId.generate()
        val first = EntityId.generate()
        val second = EntityId.generate()
        val original: GameAction = CastSpell(
            playerId = player,
            cardId = spell,
            declaredCostSlot = ChoiceSlot.KICKED,
            additionalCostPayment = AdditionalCostPayment(variableCostPermanents = listOf(first, second)),
        )
        val json = Json {
            serializersModule = engineSerializersModule
            encodeDefaults = true
        }

        val decoded = json.decodeFromString(
            GameAction.serializer(),
            json.encodeToString(GameAction.serializer(), original)
        ) as CastSpell

        decoded.declaredCostSlot shouldBe ChoiceSlot.KICKED
        decoded.additionalCostPayment?.variableCostPermanents shouldBe listOf(first, second)
        decoded.additionalCostPayment?.variableCostPermanents?.size shouldBe 2
    }
})
