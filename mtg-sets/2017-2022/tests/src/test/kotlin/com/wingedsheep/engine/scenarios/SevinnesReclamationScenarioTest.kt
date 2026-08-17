package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.AlternativeCostType
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.wwk.cards.BasiliskCollar
import com.wingedsheep.mtg.sets.definitions.mrd.cards.Bonesplitter
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.effects.ConditionalEffect
import com.wingedsheep.sdk.scripting.effects.MayEffect
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.targets.TargetObject
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * RED characterization for Sevinne's Reclamation (C19 #5).
 *
 * The production card is intentionally absent: the generic copy primitive currently loses the
 * resolving spell's target requirements when a flashback spell is moved to exile before its
 * resolution-time may-copy decision resumes. A faithful card definition must wait for a reusable
 * resolving-spell copy/persistence primitive.
 */
class SevinnesReclamationScenarioTest : FunSpec({

    val sevinneReclamation = card("Sevinne's Reclamation") {
        manaCost = "{2}{W}"
        colorIdentity = "W"
        typeLine = "Sorcery"
        oracleText = "Return target permanent card with mana value 3 or less from your graveyard to the battlefield. " +
            "If this spell was cast from a graveyard, you may copy this spell and may choose a new target for the copy.\n" +
            "Flashback {4}{W} (You may cast this card from your graveyard for its flashback cost. Then exile it.)"

        spell {
            val permanentCard = target(
                "target permanent card with mana value 3 or less from your graveyard",
                TargetObject(
                    filter = TargetFilter(
                        GameObjectFilter.Permanent.manaValueAtMost(3),
                        zone = Zone.GRAVEYARD,
                    ),
                ),
            )
            effect = Effects.Move(permanentCard, Zone.BATTLEFIELD, fromZone = Zone.GRAVEYARD)
                .then(
                    ConditionalEffect(
                        condition = Conditions.WasCastFromZone(Zone.GRAVEYARD),
                        effect = MayEffect(
                            Effects.CopyTargetSpell(target = EffectTarget.Self),
                            descriptionOverride = "You may copy this spell and choose new targets for the copy",
                        ),
                    ),
                )
        }

        keywordAbility(KeywordAbility.flashback("{4}{W}"))

        metadata {
            rarity = Rarity.RARE
            collectorNumber = "5"
            artist = "Zoltan Boros"
            imageUri = "https://cards.scryfall.io/normal/front/7/e/7e68f4df-88ce-4e09-a03c-7edf40bff167.jpg?1783932814"
        }
    }

    fun newDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + sevinneReclamation + BasiliskCollar + Bonesplitter)
        driver.initMirrorMatch(
            deck = Deck.of("Plains" to 40),
            skipMulligans = true,
            startingPlayer = 0,
        )
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun resolveUntilPausedOrEmpty(driver: GameTestDriver) {
        var guard = 0
        while (guard++ < 30 && driver.state.stack.isNotEmpty() && !driver.isPaused) {
            driver.bothPass()
        }
    }

    test("normal cast returns only a targeted permanent with mana value 3 or less") {
        val driver = newDriver()
        val you = driver.activePlayer!!
        val eligible = driver.putCardInGraveyard(you, "Basilisk Collar")
        val nonPermanent = driver.putCardInGraveyard(you, "Lightning Bolt")
        val tooExpensive = driver.putCardInGraveyard(you, "Hill Giant")
        val spell = driver.putCardInHand(you, "Sevinne's Reclamation")

        driver.giveMana(you, Color.WHITE, 1)
        driver.giveColorlessMana(you, 2)
        driver.castSpellWithTargets(
            you,
            spell,
            listOf(ChosenTarget.Card(eligible, you, com.wingedsheep.sdk.core.Zone.GRAVEYARD)),
        ).error shouldBe null
        resolveUntilPausedOrEmpty(driver)

        driver.findPermanent(you, "Basilisk Collar") shouldBe eligible
        driver.state.getZone(you, com.wingedsheep.sdk.core.Zone.GRAVEYARD) shouldNotContain eligible
        driver.state.getZone(you, com.wingedsheep.sdk.core.Zone.GRAVEYARD) shouldContain nonPermanent
        driver.state.getZone(you, com.wingedsheep.sdk.core.Zone.GRAVEYARD) shouldContain tooExpensive
    }

    test("flashback offers an explicit may-copy choice and a new target for the copy") {
        val driver = newDriver()
        val you = driver.activePlayer!!
        val first = driver.putCardInGraveyard(you, "Basilisk Collar")
        val second = driver.putCardInGraveyard(you, "Bonesplitter")
        val spell = driver.putCardInGraveyard(you, "Sevinne's Reclamation")

        driver.giveMana(you, Color.WHITE, 2)
        driver.giveColorlessMana(you, 4)
        driver.submit(
            CastSpell(
                playerId = you,
                cardId = spell,
                targets = listOf(ChosenTarget.Card(first, you, com.wingedsheep.sdk.core.Zone.GRAVEYARD)),
                useAlternativeCost = true,
                alternativeCostType = AlternativeCostType.FLASHBACK,
                paymentStrategy = PaymentStrategy.FromPool,
            )
        ).error shouldBe null
        resolveUntilPausedOrEmpty(driver)

        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        driver.submitYesNo(you, true).error shouldBe null
        val copyTargetDecision = driver.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        copyTargetDecision.legalTargets.values.flatten() shouldContain second
        driver.submitTargetSelection(you, listOf(second)).error shouldBe null
        resolveUntilPausedOrEmpty(driver)

        driver.findPermanent(you, "Basilisk Collar") shouldBe first
        driver.findPermanent(you, "Bonesplitter") shouldBe second
        driver.state.getZone(you, com.wingedsheep.sdk.core.Zone.EXILE) shouldContain spell
    }
})
