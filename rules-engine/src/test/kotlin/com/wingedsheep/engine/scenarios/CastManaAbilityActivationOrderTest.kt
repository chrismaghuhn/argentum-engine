package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivationCostComponentRefV1
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.ManaResourceRefV1
import com.wingedsheep.engine.core.PaymentAllocationV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PaymentTargetV1
import com.wingedsheep.engine.core.SourceActivationV2
import com.wingedsheep.engine.mechanics.mana.ManaAbilityIdentity
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardScript
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.GameObjectFilter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * RED characterization for CR 601.2g mana activation before 601.2h non-mana cost payment.
 *
 * The sacrifice case is deliberately expected to succeed: the selected mana source may produce
 * the mana first and then be sacrificed as the spell's additional cost. The tap case is the
 * control: the same permanent cannot be used for two tap costs in one payment.
 */
class CastManaAbilityActivationOrderTest : FunSpec({

    val sacrificeProbe = CardDefinition.instant(
        name = "Mana Before Sacrifice Probe",
        manaCost = ManaCost.parse("{G}"),
        oracleText = "As an additional cost to cast this spell, sacrifice any number of creatures.",
        script = CardScript(
            additionalCosts = listOf(
                Costs.additional.SacrificePermanents(
                    filter = GameObjectFilter.Creature,
                    minCount = 0,
                )
            )
        ),
    )

    val tapProbe = CardDefinition.instant(
        name = "Mana Before Tap Probe",
        manaCost = ManaCost.parse("{G}"),
        oracleText = "As an additional cost to cast this spell, tap a creature.",
        script = CardScript(
            additionalCosts = listOf(
                Costs.additional.TapPermanents(
                    count = 1,
                    filter = GameObjectFilter.Creature,
                )
            )
        ),
    )

    val manaCreature = card("Sacrificeable Mana Creature") {
        manaCost = "{1}"
        typeLine = "Creature — Elf"
        power = 1
        toughness = 1
        keywords(Keyword.HASTE)
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(com.wingedsheep.sdk.core.Color.GREEN)
            manaAbility = true
        }
    }

    data class Fixture(
        val driver: GameTestDriver,
        val player: EntityId,
        val source: EntityId,
        val spell: EntityId,
        val manaAbilityKey: String,
    )

    fun fixture(spell: CardDefinition): Fixture {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + manaCreature + sacrificeProbe + tapProbe)
        driver.initMirrorMatch(
            deck = Deck.of("Forest" to 40),
            skipMulligans = true,
            startingPlayer = 0,
        )
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val player = driver.activePlayer!!
        val source = driver.putPermanentOnBattlefield(player, manaCreature.name)
        val spellId = driver.putCardInHand(player, spell.name)
        val ability = manaCreature.script.activatedAbilities.single()
        return Fixture(
            driver = driver,
            player = player,
            source = source,
            spell = spellId,
            manaAbilityKey = ManaAbilityIdentity.key(ability),
        )
    }

    fun plan(fixture: Fixture): PaymentPlanV3 = PaymentPlanV3(
        activations = listOf(
            SourceActivationV2(
                sourceId = fixture.source,
                manaAbilityKey = fixture.manaAbilityKey,
                productionChoice = com.wingedsheep.engine.core.ProductionChoice(PaymentManaColor.GREEN),
                activationCostOrder = listOf(
                    ActivationCostComponentRefV1.DeterministicNonManaComponent(0),
                ),
            )
        ),
        outerAllocation = listOf(
            PaymentAllocationV1(
                target = PaymentTargetV1.OuterCostUnit(symbolIndex = 0, unitIndexWithinSymbol = 0),
                resource = ManaResourceRefV1.ActivationOutputUnit(activationIndex = 0, outputIndex = 0),
            )
        ),
    )

    test("a mana source may fund the cast before being sacrificed as an additional cost") {
        val fixture = fixture(sacrificeProbe)
        val beforeEvents = fixture.driver.events
        val result = fixture.driver.submit(
            CastSpell(
                playerId = fixture.player,
                cardId = fixture.spell,
                paymentStrategy = PaymentStrategy.ExplicitV3(paymentPlan = plan(fixture)),
                additionalCostPayment = com.wingedsheep.sdk.scripting.AdditionalCostPayment(
                    variableCostPermanents = listOf(fixture.source),
                ),
            )
        )

        println("SACRIFICE_OVERLAP_RED success=${result.isSuccess} error=${result.error}")
        result.error shouldBe "Payment source is not currently available: ${fixture.source}"
        result.isSuccess shouldBe true
        fixture.driver.state.getZone(com.wingedsheep.engine.state.ZoneKey(fixture.player, Zone.GRAVEYARD))
            .toList() shouldBe listOf(fixture.source)
        fixture.driver.state.stack.any { it.value == fixture.spell.value } shouldBe true
        fixture.driver.events shouldBe beforeEvents + result.events
    }

    test("a tap additional cost does not permit reusing the same permanent for mana") {
        val fixture = fixture(tapProbe)
        val beforeState = fixture.driver.state
        val beforeEvents = fixture.driver.events
        val result = fixture.driver.submit(
            CastSpell(
                playerId = fixture.player,
                cardId = fixture.spell,
                paymentStrategy = PaymentStrategy.ExplicitV3(paymentPlan = plan(fixture)),
                additionalCostPayment = com.wingedsheep.sdk.scripting.AdditionalCostPayment(
                    tappedPermanents = listOf(fixture.source),
                ),
            )
        )

        result.isSuccess shouldBe false
        fixture.driver.state shouldBe beforeState
        fixture.driver.events shouldBe beforeEvents
    }
})
