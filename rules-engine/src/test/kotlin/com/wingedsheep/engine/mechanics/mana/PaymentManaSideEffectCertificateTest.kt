package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.apc.cards.LlanowarWastes
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.effects.ManaRestriction
import com.wingedsheep.sdk.scripting.effects.ManaSpellRider
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Regression coverage for separating exact mana production from deterministic side effects. */
class PaymentManaSideEffectCertificateTest : FunSpec({

    val unsupportedSiblingSource = card("Certificate Unsupported Sibling Source") {
        typeLine = "Land"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddColorlessMana(1)
            manaAbility = true
        }
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(Color.BLACK)
                .then(Effects.DealDamage(DynamicAmount.XValue, EffectTarget.PlayerRef(Player.You)))
            manaAbility = true
        }
    }

    val restrictedSource = card("Certificate Restricted Source") {
        typeLine = "Land"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(
                color = Color.BLACK,
                restriction = ManaRestriction.CreatureSpellsOnly,
            )
            manaAbility = true
        }
    }

    val riderSource = card("Certificate Rider Source") {
        typeLine = "Land"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(
                color = Color.BLACK,
                riders = setOf(ManaSpellRider.MakesSpellUncounterable),
            )
            manaAbility = true
        }
    }

    val payLifeSource = card("Certificate Pay Life Source") {
        typeLine = "Land"
        activatedAbility {
            cost = Costs.Composite(Costs.Tap, Costs.PayLife(1))
            effect = Effects.AddMana(Color.BLACK)
            manaAbility = true
        }
    }

    val secondaryCostSource = card("Certificate Secondary Cost Source") {
        typeLine = "Land"
        activatedAbility {
            cost = Costs.Composite(Costs.Tap, Costs.TapAnotherPermanent())
            effect = Effects.AddMana(Color.BLACK)
            manaAbility = true
        }
    }

    val secondaryCostTarget = card("Certificate Secondary Cost Target") {
        typeLine = "Artifact"
    }

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

    fun discoverSource(
        definition: CardDefinition,
        additionalPermanents: List<CardDefinition> = emptyList(),
    ): ManaSource {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + definition + additionalPermanents)
        driver.initMirrorMatch(Deck.of("Forest" to 20), startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val player = driver.activePlayer!!
        driver.putPermanentOnBattlefield(player, definition.name)
        additionalPermanents.forEach { driver.putPermanentOnBattlefield(player, it.name) }
        return ManaSolver(driver.cardRegistry)
            .findAvailableManaSources(driver.state, player)
            .single { it.name == definition.name }
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
        driver.registerCards(TestCards.all + LlanowarWastes)
        driver.initMirrorMatch(Deck.of("Forest" to 20), startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, LlanowarWastes.name)
        val source = ManaSolver(driver.cardRegistry)
            .findAvailableManaSources(driver.state, player)
            .single { it.entityId == sourceId }

        source.paymentManaSideEffectCertificates.keys shouldBe source.paymentManaProductionProfiles.keys
        source.manaAbilityOptionsFor(Color.BLACK).single().let(ManaAbilityIdentity::key).let { key ->
            source.paymentManaProductionProfiles.getValue(key)
                .shouldBeInstanceOf<PaymentManaProductionProfile.SelectableSingleOutput>()
            source.paymentManaSideEffectCertificates.getValue(key) shouldBe
                PaymentManaSideEffectCertificate.FixedSelfDamage(1)
        }
        source.manaAbilityOptionsFor(Color.GREEN).single().let(ManaAbilityIdentity::key).let { key ->
            source.paymentManaProductionProfiles.getValue(key)
                .shouldBeInstanceOf<PaymentManaProductionProfile.SelectableSingleOutput>()
            source.paymentManaSideEffectCertificates.getValue(key) shouldBe
                PaymentManaSideEffectCertificate.FixedSelfDamage(1)
        }
        source.manaAbilityOptionsFor(null).single().let(ManaAbilityIdentity::key).let { key ->
            source.paymentManaProductionProfiles.getValue(key)
                .shouldBeInstanceOf<PaymentManaProductionProfile.SelectableSingleOutput>()
            source.paymentManaSideEffectCertificates.getValue(key) shouldBe
                PaymentManaSideEffectCertificate.NoSideEffect
        }
        source.supportsPaymentPlanV1() shouldBe true
    }

    test("the certificate rejects dynamic, choice-bearing, non-self, and multiple side effects") {
        val self = EffectTarget.PlayerRef(Player.You)

        PaymentManaSideEffectCertificateResolver.resolve(
            Effects.AddMana(Color.BLACK)
                .then(Effects.DealDamage(DynamicAmount.XValue, self)),
        ).shouldBeInstanceOf<PaymentManaSideEffectCertificate.Unsupported>()

        PaymentManaSideEffectCertificateResolver.resolve(
            Effects.AddMana(Color.BLACK)
                .then(Effects.DealDamage(1, EffectTarget.PlayerRef(Player.EachOpponent))),
        ).shouldBeInstanceOf<PaymentManaSideEffectCertificate.Unsupported>()

        PaymentManaSideEffectCertificateResolver.resolve(
            Effects.AddMana(Color.BLACK)
                .then(Effects.ChooseColorThen(Effects.GainLife(1))),
        ).shouldBeInstanceOf<PaymentManaSideEffectCertificate.Unsupported>()

        PaymentManaSideEffectCertificateResolver.resolve(
            Effects.AddMana(Color.BLACK)
                .then(Effects.DealDamage(1, self))
                .then(Effects.GainLife(1)),
        ).shouldBeInstanceOf<PaymentManaSideEffectCertificate.Unsupported>()
    }

    test("an unsupported sibling closes the complete source instead of publishing a partial domain") {
        val source = discoverSource(unsupportedSiblingSource)

        source.paymentManaProductionProfiles.size shouldBe 2
        source.paymentManaProductionProfiles.values.all {
            it !is PaymentManaProductionProfile.Unsupported
        } shouldBe true
        source.paymentManaSideEffectCertificates.values.any {
            it is PaymentManaSideEffectCertificate.Unsupported
        } shouldBe true
        source.supportsPaymentPlanV1() shouldBe false
    }

    test("restrictions, mana riders, pay-life costs, and secondary costs remain unsupported") {
        discoverSource(restrictedSource).supportsPaymentPlanV1() shouldBe false
        discoverSource(riderSource).supportsPaymentPlanV1() shouldBe false
        discoverSource(payLifeSource).supportsPaymentPlanV1() shouldBe false
        discoverSource(secondaryCostSource, listOf(secondaryCostTarget)).supportsPaymentPlanV1() shouldBe false
    }

})
