package com.wingedsheep.ai.engine

import com.wingedsheep.engine.state.components.battlefield.HasBecomeTappedComponent
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.battlefield.TargetedByControllerThisTurnComponent
import com.wingedsheep.engine.state.components.player.EquipActivationsThisTurnComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.TargetsComponent
import com.wingedsheep.sdk.core.DayNight
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.GameRng
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * What [StateProgress.digest] must and must not notice.
 *
 * The digest decides whether an action accomplished anything, and [Strategist] permanently refuses
 * an action that accomplished nothing — so a game fact the digest is blind to is not a rounding
 * error, it is an ability the AI can never use again. That asymmetry is why `normalized` names the
 * fields it *excludes* rather than the ones it reads, and this is the test that keeps it honest:
 * the excluded list is short and fixed, so it can be checked exhaustively, while the fields that
 * must count are open-ended and covered by spot-checking the turn-level riders an ability can set
 * without touching any permanent.
 */
class StateProgressTest : FunSpec({

    fun state() = GameTestDriver().apply {
        registerCards(TestCards.all)
        initMirrorMatch(deck = Deck.of("Forest" to 40), skipMulligans = true, startingPlayer = 0)
    }.state

    test("bookkeeping that every action advances is not a change") {
        val base = state()
        val here = StateProgress.digest(base)

        // Each of these moves whenever anything resolves at all, so reading them would make every
        // inert action look like progress — the exact misreading the guard exists to avoid.
        withClue("rng") { StateProgress.digest(base.copy(rng = GameRng(0x5EED))) shouldBe here }
        withClue("nextEntityId") { StateProgress.digest(base.copy(nextEntityId = 9_999L)) shouldBe here }
        withClue("nextRoutingId") { StateProgress.digest(base.copy(nextRoutingId = 1L)) shouldBe here }
        withClue("timestamp") { StateProgress.digest(base.copy(timestamp = 9_999L)) shouldBe here }

        // Whose turn it is to speak is not what is true of the board. Being blind to it is what
        // makes an action's own resolution comparable with the position it started from.
        withClue("priorityPlayerId") {
            StateProgress.digest(base.copy(priorityPlayerId = base.turnOrder[1])) shouldBe here
        }
        withClue("priorityPassedBy") {
            StateProgress.digest(base.copy(priorityPassedBy = base.turnOrder.toSet())) shouldBe here
        }
    }

    test("runtime object identity bookkeeping is not a semantic position") {
        val base = state()
        val here = StateProgress.digest(base)

        // CR 400.7 identity stamps protect locked targets at runtime, but they do not describe
        // the semantic game position. A resolution that only creates or retains those stamps
        // must remain comparable to its starting position for AI loop detection.
        withClue("nextObjectIdentityStamp") {
            StateProgress.digest(base.copy(nextObjectIdentityStamp = base.nextObjectIdentityStamp + 17L)) shouldBe here
        }
        withClue("objectIdentityStamps") {
            val objectId = base.turnOrder.first()
            StateProgress.digest(
                base.copy(objectIdentityStamps = base.objectIdentityStamps + (objectId to 999L)),
            ) shouldBe here
        }
    }

    test("target-entry identity stamps are not a semantic position") {
        val driver = GameTestDriver().apply {
            registerCards(TestCards.all)
            initMirrorMatch(deck = Deck.of("Forest" to 40), skipMulligans = true, startingPlayer = 0)
        }
        val target = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val targetChoice = ChosenTarget.Permanent(target)
        val base = driver.state

        // TargetsComponent carries both semantic locked choices and transient CR 400.7 stamps.
        // Only the latter must be invisible to loop detection.
        val stampedAtCast = base.updateEntity(target) {
            it.with(TargetsComponent(
                targets = listOf(targetChoice),
                targetEntryStamps = mapOf(target to 11L)
            ))
        }
        val stampedAfterResolution = stampedAtCast.updateEntity(target) {
            it.with(TargetsComponent(
                targets = listOf(targetChoice),
                targetEntryStamps = mapOf(target to 12L)
            ))
        }

        StateProgress.digest(stampedAfterResolution) shouldBe StateProgress.digest(stampedAtCast)
    }

    test("a turn-level rider an ability can set without touching a permanent is a change") {
        val base = state()
        val here = StateProgress.digest(base)

        // None of these live on a permanent, so nothing in the per-object walk would catch them.
        // Under the read-list this replaced they were all invisible, which would have made an
        // ability whose only effect is one of them permanently un-takeable.
        withClue("damageCantBePreventedThisTurn") {
            StateProgress.digest(base.copy(damageCantBePreventedThisTurn = true)) shouldNotBe here
        }
        withClue("spellWarpedThisTurn") {
            StateProgress.digest(base.copy(spellWarpedThisTurn = true)) shouldNotBe here
        }
        withClue("nonlandPermanentLeftBattlefieldThisTurn") {
            StateProgress.digest(base.copy(nonlandPermanentLeftBattlefieldThisTurn = true)) shouldNotBe here
        }
        withClue("playersWhoCommittedCrimeThisTurn") {
            StateProgress.digest(base.copy(playersWhoCommittedCrimeThisTurn = setOf(base.turnOrder[0]))) shouldNotBe here
        }
        withClue("dayNight") {
            StateProgress.digest(base.copy(dayNight = DayNight.NIGHT)) shouldNotBe here
        }
    }

    test("an it-happened memory on a permanent is bookkeeping, not a position") {
        // The other half of what `normalized` excludes: `IGNORED_COMPONENTS`, applied in the
        // per-object walk. Pinned here rather than only in LoopingActionAiTest so a refactor of the
        // ignore list fails in the file that documents it.
        //
        // Injected rather than produced by a real tap on purpose: `tap()` also sets
        // TappedComponent, which *is* a position change. What must be invisible is the stamp it
        // writes alongside — Aphetto Alchemist's self-untap pays its own cost back and leaves
        // nothing but that stamp behind, and a digest that read it would call the no-op progress.
        val driver = GameTestDriver().apply {
            registerCards(TestCards.all)
            initMirrorMatch(deck = Deck.of("Forest" to 40), skipMulligans = true, startingPlayer = 0)
        }
        val bears = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val base = driver.state
        val here = StateProgress.digest(base)

        withClue("HasBecomeTappedComponent") {
            val stamped = base.updateEntity(bears) {
                it.with(HasBecomeTappedComponent(base.turnNumber, timesThisTurn = 1))
            }
            StateProgress.digest(stamped) shouldBe here
        }
        withClue("TargetedByControllerThisTurnComponent") {
            val targeted = base.updateEntity(bears) {
                it.with(TargetedByControllerThisTurnComponent(setOf(driver.player1)))
            }
            StateProgress.digest(targeted) shouldBe here
        }
    }

    test("re-equipping after the turn's first equip is bookkeeping, not a position") {
        // Re-equipping an Equipment onto the creature it is already attached to changes nothing on
        // the board; the only trace it leaves is the player's equip-activation count. With equip
        // made free (Puresteel Paladin's metalcraft), reading that count made every re-equip look
        // like progress, and the engine AI re-equipped Vulshok Morningstar to the same creature
        // until the locked Akiri vs Chevill Commander game hit its step cap. Once the turn's first
        // equip is spent (count 1), further activations must not read as a new position.
        val base = state()
        val player = base.turnOrder.first()
        val once = base.updateEntity(player) { it.with(EquipActivationsThisTurnComponent(count = 1)) }
        val here = StateProgress.digest(once)

        val counted = base.updateEntity(player) { it.with(EquipActivationsThisTurnComponent(count = 7)) }

        StateProgress.digest(counted) shouldBe here
    }

    test("the equip tally counts its first activation and then stops counting") {
        // The one component that is neither read in full nor ignored wholesale, because its
        // readers only ask "any yet?": `CastPermissionUtils.applyFreeFirstEquipDiscount` tests
        // `count == 0`, so 0 -> 1 spends Forge Anew's free equip for the turn and is a game fact,
        // while 1 -> 2 -> 3 is bookkeeping no card can see. Reading the raw count is what let a
        // free equip aimed at the creature the Equipment was already on hash as a fresh position
        // every time round, which is the loop `saturated` exists to close.
        val base = state()
        val you = base.turnOrder[0]
        fun withEquips(count: Int) =
            base.updateEntity(you) { it.with(EquipActivationsThisTurnComponent(count)) }

        val none = StateProgress.digest(withEquips(0))
        val once = StateProgress.digest(withEquips(1))
        withClue("spending the turn's first equip is a change") { once shouldNotBe none }
        withClue("a second equip activation is not") { StateProgress.digest(withEquips(2)) shouldBe once }
        withClue("nor a third") { StateProgress.digest(withEquips(3)) shouldBe once }
    }

    test("mana spent is not a change, but a tapped creature is") {
        // A paid no-op always leaves its payment behind: a tapped land, maybe mana left floating.
        // Reading those let the AI re-equip Well-Worn Spatula to the creature already wearing it
        // until its lands ran out, every repetition hashing as a fresh position.
        val driver = GameTestDriver().apply {
            registerCards(TestCards.all)
            initMirrorMatch(deck = Deck.of("Forest" to 40), skipMulligans = true, startingPlayer = 0)
        }
        val you = driver.state.turnOrder[0]
        val forest = driver.putLandOnBattlefield(you, "Forest")
        val bears = driver.putCreatureOnBattlefield(you, "Grizzly Bears")
        val base = driver.state
        val here = StateProgress.digest(base)

        withClue("a tapped land") {
            StateProgress.digest(base.updateEntity(forest) { it.with(TappedComponent) }) shouldBe here
        }
        withClue("mana floating in the pool") {
            StateProgress.digest(base.updateEntity(you) { it.with(ManaPoolComponent(green = 1)) }) shouldBe here
        }
        withClue("a tapped creature can no longer block, so it is still a game fact") {
            StateProgress.digest(base.updateEntity(bears) { it.with(TappedComponent) }) shouldNotBe here
        }
    }

    test("turn and step are part of the position, so a digest can only recur inside one window") {
        val base = state()
        val here = StateProgress.digest(base)

        // This is what bounds `Strategist.positionsActedFrom`: the same board one turn later is a
        // different position, so a remembered entry can only ever match while matching means
        // going in circles.
        StateProgress.digest(base.copy(turnNumber = base.turnNumber + 1)) shouldNotBe here
    }
    test("transient stack object allocation and its orphan identity are not progress") {
        val base = state()
        val transient = com.wingedsheep.sdk.model.EntityId.generate()
        val afterResolution = base.withEntity(transient, com.wingedsheep.engine.state.ComponentContainer.EMPTY)
            .pushToStack(transient).popFromStack().second
        afterResolution.objectRef(transient) shouldNotBe null
        afterResolution.nextObjectGeneration shouldNotBe base.nextObjectGeneration
        StateProgress.digest(afterResolution) shouldBe StateProgress.digest(base)
    }

    test("a live card round trip remains progress when membership and characteristics are identical") {
        val base = state()
        val player = base.turnOrder.first()
        val library = com.wingedsheep.engine.state.ZoneKey(player, com.wingedsheep.sdk.core.Zone.LIBRARY)
        val graveyard = com.wingedsheep.engine.state.ZoneKey(player, com.wingedsheep.sdk.core.Zone.GRAVEYARD)
        val exile = com.wingedsheep.engine.state.ZoneKey(player, com.wingedsheep.sdk.core.Zone.EXILE)
        val card = base.getZone(library).first()
        val placed = base.moveToZone(card, library, graveyard)
        val before = placed.copy(zones = placed.zones + (exile to emptyList()))
        val after = before.moveToZone(card, graveyard, exile).moveToZone(card, exile, graveyard)
            .withEntity(card, before.getEntity(card)!!)
        after.zones shouldBe before.zones
        after.entities shouldBe before.entities
        after.objectRef(card) shouldNotBe before.objectRef(card)
        StateProgress.digest(after) shouldNotBe StateProgress.digest(before)
    }

})
