package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.apc.cards.LlanowarWastes
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** RED coverage for separating exact mana production from deterministic side effects. */
class PaymentManaSideEffectCertificateTest : FunSpec({

    val fixedPainSource = card("Certificate Fixed Pain Source") {
        typeLine = "Land"
        activatedAbility {
            cost = com.wingedsheep.sdk.dsl.Costs.Tap
            effect = Effects.AddMana(Color.BLACK)
                .then(Effects.DealDamage(1, com.wingedsheep.sdk.scripting.targets.EffectTarget.PlayerRef(
                    com.wingedsheep.sdk.scripting.references.Player.You,
                )))
            manaAbility = true
        }
    }

    test("fixed self-damage does not make exact mana production unsupported") {
        val ability = fixedPainSource.activatedAbilities.single()

        PaymentManaProductionProfileResolver.resolve(ability.effect, setOf(Color.BLACK)) shouldBe
            PaymentManaProductionProfile.SelectableSingleOutput(
                setOf(com.wingedsheep.engine.core.PaymentManaColor.BLACK),
            )
    }

    test("a real mixed pain source is complete when every current ability is exact") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + fixedPainSource + LlanowarWastes)
        driver.initMirrorMatch(Deck.of("Forest" to 20), startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, LlanowarWastes.name)
        val source = ManaSolver(driver.cardRegistry)
            .findAvailableManaSources(driver.state, player)
            .single { it.entityId == sourceId }

        source.manaAbilityOptionsFor(Color.BLACK).single().let(ManaAbilityIdentity::key).let { key ->
            source.paymentManaProductionProfiles.getValue(key)
                .shouldBeInstanceOf<PaymentManaProductionProfile.SelectableSingleOutput>()
        }
        source.manaAbilityOptionsFor(Color.GREEN).single().let(ManaAbilityIdentity::key).let { key ->
            source.paymentManaProductionProfiles.getValue(key)
                .shouldBeInstanceOf<PaymentManaProductionProfile.SelectableSingleOutput>()
        }
        source.manaAbilityOptionsFor(null).single().let(ManaAbilityIdentity::key).let { key ->
            source.paymentManaProductionProfiles.getValue(key)
                .shouldBeInstanceOf<PaymentManaProductionProfile.SelectableSingleOutput>()
        }
        source.supportsPaymentPlanV1() shouldBe true
    }
})
