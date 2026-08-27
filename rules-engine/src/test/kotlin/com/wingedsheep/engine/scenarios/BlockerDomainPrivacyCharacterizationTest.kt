package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.mechanics.layers.Layer
import com.wingedsheep.engine.mechanics.layers.SerializableModification
import com.wingedsheep.engine.mechanics.layers.addFloatingEffect
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardScript
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.BlockTax
import com.wingedsheep.sdk.scripting.Duration
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Characterizes the blocker-domain privacy boundary for face-down permanents.
 *
 * A face-down permanent remains a public battlefield object, but its underlying definition is not
 * part of the defender's information set. The Rules producer must therefore not resolve a global
 * blocker constraint from that hidden definition.
 */
class BlockerDomainPrivacyCharacterizationTest : FunSpec({

    val HiddenBlockTaxSource = CardDefinition(
        name = "Hidden Block Tax Source",
        manaCost = ManaCost.ZERO,
        typeLine = TypeLine(cardTypes = setOf(CardType.ENCHANTMENT)),
        oracleText = "Creatures can't block unless their controller pays {2}.",
        script = CardScript(staticAbilities = listOf(BlockTax(amountPerBlocker = DynamicAmount.Fixed(2)))),
    )

    test("a face-down opponent permanent cannot change the public global blocker domain") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.initMirrorMatch(Deck.of("Forest" to 40))

        val attackerPlayer = driver.activePlayer!!
        val defendingPlayer = driver.getOpponent(attackerPlayer)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val attacker = driver.putCreatureOnBattlefield(attackerPlayer, "Grizzly Bears")
        val blocker = driver.putCreatureOnBattlefield(defendingPlayer, "Grizzly Bears")
        driver.removeSummoningSickness(attacker)
        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(attackerPlayer, listOf(attacker), defendingPlayer).isSuccess shouldBe true
        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)

        val hiddenPermanent = driver.putPermanentOnBattlefield(defendingPlayer, "Forest")
        driver.replaceState(
            driver.state.updateEntity(hiddenPermanent) { it.with(FaceDownComponent) },
        )
        val forestDomain = blockerDomain(driver, defendingPlayer)

        replaceHiddenDefinition(driver, hiddenPermanent, "Dueling Grounds")
        val duelingGroundsDomain = blockerDomain(driver, defendingPlayer)

        // The public state has the same face-down object and the same combatants in both cases.
        // Dueling Grounds' hidden BlockerCountLimit must not leak into the defender's domain.
        forestDomain.globalMaxBlockers shouldBe null
        duelingGroundsDomain.globalMaxBlockers shouldBe forestDomain.globalMaxBlockers
        duelingGroundsDomain.blockerOrder shouldBe forestDomain.blockerOrder
        duelingGroundsDomain.attackerOrder shouldBe forestDomain.attackerOrder
        duelingGroundsDomain.blockerToAttackers shouldBe forestDomain.blockerToAttackers
        forestDomain.blockerOrder.contains(blocker) shouldBe true
    }

    test("a face-down attacking creature cannot expose its printed block restriction") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.initMirrorMatch(Deck.of("Forest" to 40))

        val attackerPlayer = driver.activePlayer!!
        val defendingPlayer = driver.getOpponent(attackerPlayer)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val attacker = driver.putCreatureOnBattlefield(attackerPlayer, "Grizzly Bears")
        driver.putCreatureOnBattlefield(defendingPlayer, "Grizzly Bears")
        driver.removeSummoningSickness(attacker)
        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(attackerPlayer, listOf(attacker), defendingPlayer).isSuccess shouldBe true
        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)

        driver.replaceState(
            driver.state.updateEntity(attacker) { it.with(FaceDownComponent) },
        )
        val neutralFaceDownDomain = blockerDomain(driver, defendingPlayer)

        replaceHiddenDefinition(driver, attacker, "Graxiplon")
        val restrictedFaceDownDomain = blockerDomain(driver, defendingPlayer)

        // Graxiplon's printed restriction is not part of the defender's information set once the
        // attacking creature is face down. Its public 2/2 characteristics must yield the same
        // blocker relation as another face-down creature with no hidden restriction.
        restrictedFaceDownDomain.attackerOrder shouldBe neutralFaceDownDomain.attackerOrder
        restrictedFaceDownDomain.blockerOrder shouldBe neutralFaceDownDomain.blockerOrder
        restrictedFaceDownDomain.blockerToAttackers shouldBe neutralFaceDownDomain.blockerToAttackers
    }

    test("a face-down opponent permanent cannot change the blocker requirement threshold") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + HiddenBlockTaxSource)
        driver.initMirrorMatch(Deck.of("Forest" to 40))

        val attackerPlayer = driver.activePlayer!!
        val defendingPlayer = driver.getOpponent(attackerPlayer)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val attacker = driver.putCreatureOnBattlefield(attackerPlayer, "Grizzly Bears")
        driver.putCreatureOnBattlefield(defendingPlayer, "Grizzly Bears")
        driver.removeSummoningSickness(attacker)
        val hiddenPermanent = driver.putPermanentOnBattlefield(attackerPlayer, "Forest")
        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(attackerPlayer, listOf(attacker), defendingPlayer).isSuccess shouldBe true
        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)

        driver.replaceState(
            driver.state
                .updateEntity(hiddenPermanent) { it.with(FaceDownComponent) }
                .addFloatingEffect(
                    layer = Layer.ABILITY,
                    modification = SerializableModification.MustBeBlockedIfAble,
                    affectedEntities = setOf(attacker),
                    duration = Duration.EndOfTurn,
                    context = EffectContext(sourceId = attacker, controllerId = attackerPlayer),
                ),
        )
        val forestDomain = blockerDomain(driver, defendingPlayer)

        replaceHiddenDefinition(driver, hiddenPermanent, "Hidden Block Tax Source")
        val blockTaxDomain = blockerDomain(driver, defendingPlayer)

        forestDomain.minimumSatisfiedRequirementCount shouldBe 1
        blockTaxDomain.minimumSatisfiedRequirementCount shouldBe forestDomain.minimumSatisfiedRequirementCount
        blockTaxDomain.canDeclareZeroBlockers shouldBe forestDomain.canDeclareZeroBlockers
        blockTaxDomain.blockerToAttackers shouldBe forestDomain.blockerToAttackers
    }
})

private fun blockerDomain(
    driver: GameTestDriver,
    defendingPlayer: com.wingedsheep.sdk.model.EntityId,
) = driver.legalActions(defendingPlayer)
    .single { it.action is com.wingedsheep.engine.core.DeclareBlockers }
    .blockerDeclarationDomain!!

private fun replaceHiddenDefinition(
    driver: GameTestDriver,
    cardId: com.wingedsheep.sdk.model.EntityId,
    cardName: String,
) {
    val replacement = driver.cardRegistry.requireCard(cardName)
    driver.replaceState(
        driver.state.updateEntity(cardId) { container ->
            val card = checkNotNull(container.get<CardComponent>())
            container.with(
                card.copy(
                    cardDefinitionId = replacement.name,
                    name = replacement.name,
                    manaCost = replacement.manaCost,
                    typeLine = replacement.typeLine,
                    oracleText = replacement.oracleText,
                    baseStats = replacement.creatureStats,
                    baseKeywords = replacement.keywords,
                    baseFlags = replacement.flags,
                    colors = replacement.colors,
                    spellEffect = replacement.spellEffect,
                    hasNonManaActivatedAbility = replacement.hasNonManaActivatedAbility,
                    hasActivatedAbility = replacement.hasActivatedAbility,
                    originalSetCode = replacement.setCode,
                    hasAdventure = replacement.isAdventure,
                ),
            )
        },
    )
}
