package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.state.components.identity.CopyOfComponent
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.DynamicAmounts
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardScript
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.AdditionalCost
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import com.wingedsheep.sdk.scripting.EventPattern.SpellCastEvent
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.TriggeredAbility
import com.wingedsheep.sdk.scripting.costs.CostAtom
import com.wingedsheep.sdk.scripting.costs.PermanentCostAction
import com.wingedsheep.sdk.scripting.costs.VariableCostMeasure
import com.wingedsheep.sdk.scripting.effects.StormCopyEffect
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull

/**
 * RED characterization for Plumb the Forbidden (STX #81).
 *
 * Oracle:
 * "As an additional cost to cast this spell, you may sacrifice one or more creatures. When you
 * do, copy this spell for each creature sacrificed this way. You draw a card and lose 1 life."
 *
 * The fixture deliberately expresses the cast-time choice with the generic variable-permanents
 * atom and expresses copying with the generic spell-copy effect. Every sacrifice list is supplied
 * by the test; no test asks the driver to infer or auto-select a creature.
 */
class PlumbTheForbiddenScenarioTest : FunSpec({

    val sacrificeCreature = CardDefinition.creature(
        name = "Plumb Sacrifice Probe",
        manaCost = ManaCost.parse("{1}"),
        subtypes = setOf(Subtype.HUMAN),
        power = 1,
        toughness = 1,
    )

    fun plumbProbe(name: String, withFixedStormCopyTrigger: Boolean = false): CardDefinition {
        val spellEffect = Effects.Composite(
            Effects.DrawCards(1, EffectTarget.Controller),
            Effects.LoseLife(1, EffectTarget.Controller),
        )
        val additionalCosts = listOf(
            AdditionalCost.Atom(
                CostAtom.VariablePermanents(
                    filter = GameObjectFilter.Creature,
                    minCount = 0,
                    excludeSelf = false,
                    action = PermanentCostAction.SACRIFICE,
                    xMeasure = VariableCostMeasure.COUNT,
                )
            )
        )
        val castCopyTrigger = if (withFixedStormCopyTrigger) {
            listOf(
                TriggeredAbility(
                    id = AbilityId.generate(),
                    trigger = Triggers.WhenYouCastThisSpell().event,
                    binding = TriggerBinding.SELF,
                    effect = StormCopyEffect(
                        copyCount = 1,
                        spellEffect = spellEffect,
                        spellName = name,
                    ),
                    activeZones = setOf(Zone.STACK),
                )
            )
        } else {
            emptyList()
        }
        return CardDefinition.instant(
            name = name,
            manaCost = ManaCost.parse("{1}{B}"),
            oracleText = "As an additional cost to cast this spell, you may sacrifice one or more creatures. " +
                "When you do, copy this spell for each creature sacrificed this way. You draw a card and lose 1 life.",
            script = CardScript(
                spellEffect = spellEffect,
                additionalCosts = additionalCosts,
                triggeredAbilities = castCopyTrigger,
            ),
        )
    }

    val plumbTheForbiddenProbe = plumbProbe("Plumb the Forbidden")
    val plumbPayloadProbe = plumbProbe("Plumb the Forbidden Payload Probe", withFixedStormCopyTrigger = true)

    fun copyObserver(name: String, copies: DynamicAmount): CardDefinition = CardDefinition.enchantment(
        name = name,
        manaCost = ManaCost.ZERO,
        oracleText = "Whenever you cast a spell, copy it.",
        script = CardScript(
            triggeredAbilities = listOf(
                TriggeredAbility(
                    id = AbilityId.generate(),
                    trigger = SpellCastEvent(
                        spellFilter = GameObjectFilter.Any,
                        player = Player.You,
                    ),
                    binding = TriggerBinding.ANY,
                    effect = Effects.CopyTargetSpell(
                        target = EffectTarget.TriggeringEntity,
                        copies = copies,
                    ),
                    activeZones = setOf(Zone.BATTLEFIELD),
                )
            )
        ),
    )

    fun createDriver(copyObserver: CardDefinition? = null): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(
            TestCards.all + listOfNotNull(
                sacrificeCreature,
                plumbTheForbiddenProbe,
                plumbPayloadProbe,
                copyObserver,
            )
        )
        driver.initMirrorMatch(Deck.of("Swamp" to 40))
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        if (copyObserver != null) {
            driver.putPermanentOnBattlefield(driver.activePlayer!!, copyObserver.name)
        }
        return driver
    }

    fun castPlumb(
        driver: GameTestDriver,
        player: EntityId,
        sacrificedCreatures: List<EntityId>,
        cardName: String = plumbTheForbiddenProbe.name,
    ): EntityId {
        val card = driver.putCardInHand(player, cardName)
        driver.giveMana(player, Color.BLACK, 2)
        driver.submit(
            CastSpell(
                playerId = player,
                cardId = card,
                additionalCostPayment = AdditionalCostPayment(
                    variableCostPermanents = sacrificedCreatures,
                ),
                paymentStrategy = PaymentStrategy.FromPool,
            )
        ).error shouldBe null
        return card
    }

    fun resolveAllStackObjects(driver: GameTestDriver) {
        var passes = 0
        while (driver.state.stack.isNotEmpty()) {
            driver.bothPass()
            passes++
            if (passes > 30) error("Stack did not drain")
        }
    }

    test("zero sacrifices is an explicit choice and draws once while losing one life") {
        val driver = createDriver(copyObserver("Plumb Dynamic Copy Probe", DynamicAmounts.permanentsSacrificedThisWay()))
        val player = driver.activePlayer!!
        val handBeforeCast = driver.getHandSize(player)

        castPlumb(driver, player, sacrificedCreatures = emptyList())
        resolveAllStackObjects(driver)

        (driver.getHandSize(player) - handBeforeCast) shouldBe 1
        driver.getLifeTotal(player) shouldBe 19
    }

    test("one explicitly named sacrifice produces one copy, one extra draw, and one extra life loss") {
        val driver = createDriver(copyObserver("Plumb Dynamic Copy Probe", DynamicAmounts.permanentsSacrificedThisWay()))
        val player = driver.activePlayer!!
        val sacrificeA = driver.putCreatureOnBattlefield(player, sacrificeCreature.name)
        val handBeforeCast = driver.getHandSize(player)

        castPlumb(driver, player, sacrificedCreatures = listOf(sacrificeA))
        resolveAllStackObjects(driver)

        (driver.getHandSize(player) - handBeforeCast) shouldBe 2
        driver.getLifeTotal(player) shouldBe 18
    }

    test("three explicitly named sacrifices produce three copies, three extra draws, and three extra life losses") {
        val driver = createDriver(copyObserver("Plumb Dynamic Copy Probe", DynamicAmounts.permanentsSacrificedThisWay()))
        val player = driver.activePlayer!!
        val sacrificeA = driver.putCreatureOnBattlefield(player, sacrificeCreature.name)
        val sacrificeB = driver.putCreatureOnBattlefield(player, sacrificeCreature.name)
        val sacrificeC = driver.putCreatureOnBattlefield(player, sacrificeCreature.name)
        val handBeforeCast = driver.getHandSize(player)

        castPlumb(driver, player, sacrificedCreatures = listOf(sacrificeA, sacrificeB, sacrificeC))
        resolveAllStackObjects(driver)

        (driver.getHandSize(player) - handBeforeCast) shouldBe 4
        driver.getLifeTotal(player) shouldBe 16
    }

    test("cast actions publish every eligible sacrifice explicitly instead of hiding the variable choice") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val sacrificeA = driver.putCreatureOnBattlefield(player, sacrificeCreature.name)
        val sacrificeB = driver.putCreatureOnBattlefield(player, sacrificeCreature.name)
        val card = driver.putCardInHand(player, plumbTheForbiddenProbe.name)
        driver.giveMana(player, Color.BLACK, 2)

        val casts = driver.legalActions(player)
            .filter { action ->
                val cast = action.action as? CastSpell
                cast?.cardId == card
            }
        casts.size shouldBe 1
        val costInfo = casts.single().additionalCostInfo.shouldNotBeNull()
        costInfo.validSacrificeTargets shouldBe listOf(sacrificeA, sacrificeB)
        costInfo.sacrificeCount shouldBe 0
    }

    test("a copied spell retains the explicitly chosen sacrifice payload") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val sacrificeA = driver.putCreatureOnBattlefield(player, sacrificeCreature.name)
        val sacrificeB = driver.putCreatureOnBattlefield(player, sacrificeCreature.name)
        val card = castPlumb(
            driver,
            player,
            sacrificedCreatures = listOf(sacrificeA, sacrificeB),
            cardName = plumbPayloadProbe.name,
        )

        val source = driver.state.getEntity(card)!!.get<SpellOnStackComponent>()!!
        source.sacrificedPermanents.map { snapshot -> snapshot.entityId } shouldBe listOf(sacrificeA, sacrificeB)

        driver.state.stack.count { entityId ->
            driver.state.getEntity(entityId)?.get<TriggeredAbilityOnStackComponent>() != null
        } shouldBe 1

        driver.bothPass()

        val copies = driver.state.stack.filter { entityId ->
            driver.state.getEntity(entityId)?.has<CopyOfComponent>() == true
        }
        copies.size shouldBe 1
        val copy = driver.state.getEntity(copies.single())!!.get<SpellOnStackComponent>()!!
        copy.sacrificedPermanents.map { snapshot -> snapshot.entityId } shouldBe listOf(sacrificeA, sacrificeB)
    }
})
