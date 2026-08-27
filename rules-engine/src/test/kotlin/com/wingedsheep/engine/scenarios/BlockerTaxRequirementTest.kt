package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.engine.legalactions.BlockerDeclarationDomainValidator
import com.wingedsheep.engine.legalactions.BlockerDeclarationValidationResult
import com.wingedsheep.engine.mechanics.layers.Layer
import com.wingedsheep.engine.mechanics.layers.SerializableModification
import com.wingedsheep.engine.mechanics.layers.addFloatingEffect
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CreatureStats
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.CardScript
import com.wingedsheep.sdk.scripting.BlockTax
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import com.wingedsheep.sdk.scripting.Duration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Regression coverage for CR 509.1c's blocking-cost exemption. */
class BlockerTaxRequirementTest : FunSpec({

    val TaxingAttacker = CardDefinition(
        name = "Taxing Attacker",
        manaCost = ManaCost.ZERO,
        typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE), subtypes = setOf(Subtype("Bear"))),
        oracleText = "Creatures can't block unless their controller pays {2} for each creature.",
        creatureStats = CreatureStats(2, 2),
        script = CardScript.creature(
            staticAbilities = listOf(BlockTax(amountPerBlocker = DynamicAmount.Fixed(2))),
        ),
    )

    test("a tax-bearing block is not required solely to satisfy a blocking requirement") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + TaxingAttacker)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40))

        val attackerPlayer = driver.activePlayer!!
        val defendingPlayer = driver.getOpponent(attackerPlayer)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val attacker = driver.putCreatureOnBattlefield(attackerPlayer, "Taxing Attacker")
        val blocker = driver.putCreatureOnBattlefield(defendingPlayer, "Grizzly Bears")
        driver.removeSummoningSickness(attacker)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(attackerPlayer, listOf(attacker), defendingPlayer).isSuccess shouldBe true
        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)

        driver.replaceState(
            driver.state.addFloatingEffect(
                layer = Layer.ABILITY,
                modification = SerializableModification.MustBeBlockedIfAble,
                affectedEntities = setOf(attacker),
                duration = Duration.EndOfTurn,
                context = EffectContext(sourceId = attacker, controllerId = attackerPlayer),
            )
        )

        val declaration = driver.legalActions(defendingPlayer)
            .single { it.action is com.wingedsheep.engine.core.DeclareBlockers }
        val domain = declaration.blockerDeclarationDomain!!

        // The taxed edge remains a voluntary public choice; only the requirement threshold
        // excludes it from the cost-free 509.1c maximum.
        domain.blockerToAttackers[blocker] shouldBe listOf(attacker)
        BlockerDeclarationDomainValidator.validate(
            domain,
            DeclareBlockers(defendingPlayer, mapOf(blocker to listOf(attacker))),
        ) shouldBe BlockerDeclarationValidationResult.Accepted
        domain.minimumSatisfiedRequirementCount shouldBe 0
        domain.canDeclareZeroBlockers shouldBe true
        driver.declareBlockers(defendingPlayer, emptyMap()).isSuccess shouldBe true
    }
})
