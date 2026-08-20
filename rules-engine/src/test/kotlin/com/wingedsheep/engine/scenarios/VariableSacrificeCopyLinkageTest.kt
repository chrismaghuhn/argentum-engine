package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActionProcessor
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CopyOfComponent
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.DynamicAmounts
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardScript
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/** Focused characterization for the generic variable-sacrifice → spell-copy linkage. */
class VariableSacrificeCopyLinkageTest : FunSpec({

    val sacrificeCreature = CardDefinition.creature(
        name = "Variable Copy Sacrifice Probe",
        manaCost = ManaCost.parse("{1}"),
        subtypes = setOf(Subtype.HUMAN),
        power = 1,
        toughness = 1,
    )

    val spell = CardDefinition.instant(
        name = "Variable Copy Spell Probe",
        manaCost = ManaCost.parse("{1}{B}"),
        oracleText = "As an additional cost to cast this spell, you may sacrifice one or more creatures. You draw a card and lose 1 life.",
        script = CardScript(
            spellEffect = Effects.Composite(
                Effects.DrawCards(1, EffectTarget.Controller),
                Effects.LoseLife(1, EffectTarget.Controller),
            ),
            additionalCosts = listOf(
                Costs.additional.SacrificePermanents(
                    filter = GameObjectFilter.Creature,
                    minCount = 0,
                )
            ),
            costPaidLinkedTriggers = listOf(
                Triggers.costPaidLinkedTrigger(
                    effect = Effects.CopyTargetSpell(
                        target = EffectTarget.TriggeringEntity,
                        copies = DynamicAmounts.permanentsSacrificedThisWay(),
                    ),
                )
            ),
        ),
    )

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + sacrificeCreature + spell)
        driver.initMirrorMatch(Deck.of("Swamp" to 40), skipMulligans = true)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun castSpell(driver: GameTestDriver, player: EntityId, sacrificed: List<EntityId>): EntityId {
        val card = driver.putCardInHand(player, spell.name)
        driver.giveMana(player, Color.BLACK, 2)
        driver.submit(
            CastSpell(
                playerId = player,
                cardId = card,
                additionalCostPayment = AdditionalCostPayment(variableCostPermanents = sacrificed),
                paymentStrategy = PaymentStrategy.FromPool,
            )
        ).error shouldBe null
        return card
    }

    fun resolveAllStackObjects(driver: GameTestDriver) {
        var passes = 0
        while (driver.state.stack.isNotEmpty()) {
            driver.bothPass()
            if (++passes > 30) error("Stack did not drain")
        }
    }

    test("declining the optional cost creates no linked trigger and no copy") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val handBeforeCast = driver.getHandSize(player)

        castSpell(driver, player, emptyList())

        driver.state.stack.count { id ->
            driver.state.getEntity(id)?.has<TriggeredAbilityOnStackComponent>() == true
        } shouldBe 0
        driver.state.stack.count { id ->
            driver.state.getEntity(id)?.has<CopyOfComponent>() == true
        } shouldBe 0
        resolveAllStackObjects(driver)

        (driver.getHandSize(player) - handBeforeCast) shouldBe 1
        driver.getLifeTotal(player) shouldBe 19
    }

    test("one explicitly selected sacrifice copies the spell once") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val sacrifice = driver.putCreatureOnBattlefield(player, sacrificeCreature.name)
        val handBeforeCast = driver.getHandSize(player)

        castSpell(driver, player, listOf(sacrifice))
        driver.bothPass()

        val copies = driver.state.stack.filter { id ->
            driver.state.getEntity(id)?.has<CopyOfComponent>() == true
        }
        copies.size shouldBe 1
        val sourcePayload = driver.state.getEntity(driver.state.stack.first { id ->
            driver.state.getEntity(id)?.has<CopyOfComponent>() != true &&
                driver.state.getEntity(id)?.get<SpellOnStackComponent>()
                    ?.sacrificedPermanents?.isNotEmpty() == true
        })!!.get<SpellOnStackComponent>()!!.sacrificedPermanents
        driver.state.getEntity(copies.single())!!.get<SpellOnStackComponent>()!!
            .sacrificedPermanents shouldBe sourcePayload

        resolveAllStackObjects(driver)

        (driver.getHandSize(player) - handBeforeCast) shouldBe 2
        driver.getLifeTotal(player) shouldBe 18
    }

    test("three explicitly selected sacrifices copy the spell three times") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val sacrifices = List(3) { driver.putCreatureOnBattlefield(player, sacrificeCreature.name) }
        val handBeforeCast = driver.getHandSize(player)

        castSpell(driver, player, sacrifices)
        driver.state.stack.count { id ->
            driver.state.getEntity(id)?.has<CopyOfComponent>() == true
        } shouldBe 0
        // Change the board after payment but before the linked ability resolves. The copy
        // count must remain the frozen completed-payment count, not a later battlefield count.
        driver.putCreatureOnBattlefield(player, sacrificeCreature.name)
        driver.bothPass()
        driver.state.stack.count { id ->
            driver.state.getEntity(id)?.has<CopyOfComponent>() == true
        } shouldBe 3
        resolveAllStackObjects(driver)

        (driver.getHandSize(player) - handBeforeCast) shouldBe 4
        driver.getLifeTotal(player) shouldBe 16
    }

    test("the linked trigger keeps the source LKI when the original spell is countered first") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val sacrifices = List(3) { driver.putCreatureOnBattlefield(player, sacrificeCreature.name) }
        val counterspell = driver.putCardInHand(opponent, "Counterspell")
        driver.giveMana(opponent, Color.BLUE, 2)

        val spellId = castSpell(driver, player, sacrifices)
        driver.passPriority(player)
        driver.submit(
            CastSpell(
                playerId = opponent,
                cardId = counterspell,
                targets = listOf(ChosenTarget.Spell(spellId)),
                paymentStrategy = PaymentStrategy.FromPool,
            )
        ).error shouldBe null

        // Counterspell resolves while the cost-linked trigger remains above the original spell.
        driver.bothPass().error shouldBe null
        driver.state.stack shouldBe listOf(driver.state.stack.last())
        driver.state.getEntity(spellId)?.has<SpellOnStackComponent>() shouldBe false

        val linkedTrigger = driver.state.getEntity(driver.state.stack.single())!!
            .get<TriggeredAbilityOnStackComponent>()!!
        linkedTrigger.resolvingSpellCopyPayload?.spell?.sacrificedPermanents
            ?.map { it.entityId } shouldBe sacrifices

        // The LKI payload must remain serializable after the source spell has left the stack.
        val json = Json {
            serializersModule = engineSerializersModule
            encodeDefaults = true
            allowStructuredMapKeys = true
        }
        val restoredState = json.decodeFromString(
            GameState.serializer(),
            json.encodeToString(GameState.serializer(), driver.state),
        )
        restoredState shouldBe driver.state
        driver.replaceState(restoredState)

        // The trigger must use the frozen source payload even though the original entity is gone.
        driver.bothPass().error shouldBe null
        driver.state.stack.count { id ->
            driver.state.getEntity(id)?.has<CopyOfComponent>() == true
        } shouldBe sacrifices.size
    }

    test("the cast payload records the exact selected sacrifice snapshots") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val sacrifices = List(2) { driver.putCreatureOnBattlefield(player, sacrificeCreature.name) }
        val spellId = castSpell(driver, player, sacrifices)

        driver.state.getEntity(spellId)!!.get<SpellOnStackComponent>()!!
            .sacrificedPermanents.map { it.entityId } shouldBe sacrifices
    }

    test("the linked-trigger stack round-trips and replays deterministically") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val sacrifices = List(2) { driver.putCreatureOnBattlefield(player, sacrificeCreature.name) }
        castSpell(driver, player, sacrifices)

        val json = Json {
            serializersModule = engineSerializersModule
            encodeDefaults = true
            allowStructuredMapKeys = true
        }
        val serializedState = driver.state
        val restoredState = json.decodeFromString(
            GameState.serializer(),
            json.encodeToString(GameState.serializer(), serializedState),
        )
        restoredState shouldBe serializedState

        val processor = ActionProcessor(driver.cardRegistry)
        var original = serializedState
        var restored = restoredState
        var passes = 0
        while (original.stack.isNotEmpty()) {
            val originalResult = processor.process(
                original,
                PassPriority(original.priorityPlayerId!!),
            ).result
            val restoredResult = processor.process(
                restored,
                PassPriority(restored.priorityPlayerId!!),
            ).result

            originalResult.error shouldBe null
            restoredResult.error shouldBe null
            restoredResult.state shouldBe originalResult.state
            restoredResult.events shouldBe originalResult.events
            original = originalResult.state
            restored = restoredResult.state
            if (++passes > 20) error("Reflexive copy replay did not drain")
        }
        restored shouldBe original
    }
})
