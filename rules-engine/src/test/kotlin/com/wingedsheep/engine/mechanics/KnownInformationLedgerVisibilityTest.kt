package com.wingedsheep.engine.mechanics

import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.LibraryShuffledEvent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.LookAtTopOfLibrary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Regression coverage for current Visibility-authorized library knowledge. */
class KnownInformationLedgerVisibilityTest : FunSpec({
    val LensOfClarity = card("History B Lens of Clarity") {
        manaCost = "{0}"
        typeLine = "Artifact"

        staticAbility {
            ability = LookAtTopOfLibrary
        }
    }

    fun driver(): GameTestDriver = GameTestDriver().also {
        it.registerCards(TestCards.all)
        it.registerCard(LensOfClarity)
    }

    test("HISTB-REVIEW-21 continuous top-library visibility is acquired and refreshed after shuffle") {
        val game = driver()
        game.initMirrorMatch(
            deck = Deck.of("Island" to 20, "Plains" to 20),
            startingLife = 20,
        )
        val player = game.activePlayer!!
        game.passPriorityUntil(Step.PRECOMBAT_MAIN)
        game.putPermanentOnBattlefield(player, "History B Lens of Clarity")
        val firstTop = game.putCardOnTopOfLibrary(player, "Lightning Bolt")
        val libraryKey = com.wingedsheep.engine.state.ZoneKey(player, Zone.LIBRARY)
        val stamped = game.state
            .removeFromZone(libraryKey, firstTop)
            .addToZone(libraryKey, firstTop)
        game.replaceState(
            stamped.copy(
                zones = stamped.zones +
                    (libraryKey to listOf(firstTop) + stamped.getLibrary(player).filterNot { it == firstTop })
            )
        )

        val visible = KnownInformationLedger.applyAfterAction(
            beforeState = game.state,
            result = ExecutionResult.success(game.state),
            cardRegistry = game.cardRegistry,
        ).state
        val firstFact = KnownInformationLedger.forPlayer(visible, player).activeFacts.single {
            it.subjectEntityId == firstTop &&
                it.factKind == com.wingedsheep.engine.state.components.player.KnownInformationFactKind.IDENTITY
        }

        val shuffled = visible.copy(
            zones = visible.zones + (libraryKey to visible.getLibrary(player).reversed()),
        )
        val after = KnownInformationLedger.applyAfterAction(
            beforeState = visible,
            result = ExecutionResult.success(shuffled, listOf(LibraryShuffledEvent(player))),
            cardRegistry = game.cardRegistry,
        ).state
        val newTop = after.getLibrary(player).first()

        KnownInformationLedger.forPlayer(after, player).activeFacts.any {
            it.subjectEntityId == newTop &&
                it.factKind == com.wingedsheep.engine.state.components.player.KnownInformationFactKind.IDENTITY
        } shouldBe true
        KnownInformationLedger.forPlayer(after, player).activeFacts.none {
            it.subjectEntityId == firstTop &&
                it.objectIdentityStamp == firstFact.objectIdentityStamp &&
                it.factKind == com.wingedsheep.engine.state.components.player.KnownInformationFactKind.IDENTITY
        } shouldBe true
    }
})
