package com.wingedsheep.engine.event

import com.wingedsheep.engine.core.ActionProcessor
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.ResolvedEvent
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.handlers.ContinuationHandler
import com.wingedsheep.engine.mechanics.stack.StackResolver
import com.wingedsheep.engine.mechanics.sba.zone.PhantomCardCopiesCheck
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.CopyOfComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.MayEffect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Characterizes the current state boundary when an instant/sorcery resolution pauses for input.
 *
 * These tests intentionally observe the existing behavior. The ordinary and copy paths both
 * expose a pending effect decision after the stack spell has already been disposed; the copy path
 * additionally leaves the copy in an SBA-eligible destination before the phantom-copy SBA runs.
 * The Rules-required
 * ordering is recorded in the accompanying characterization report, not asserted as a production
 * fix in this test-only slice.
 */
class PausedSpellResolutionDispositionRulesCharacterizationTest : FunSpec({

    data class Fixture(
        val state: GameState,
        val playerId: EntityId,
        val spellId: EntityId,
        val stamp: Long,
    )

    fun fixture(copy: Boolean): Fixture {
        val playerId = EntityId.generate()
        val opponentId = EntityId.generate()
        val spellId = EntityId.generate()
        val card = CardComponent(
            cardDefinitionId = "paused-spell-resolution-characterization",
            name = "Paused Spell Resolution Characterization",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine.instant(),
            oracleText = "",
            ownerId = playerId,
        )
        var spell = ComponentContainer.of(
            card,
            OwnerComponent(playerId),
            ControllerComponent(playerId),
            SpellOnStackComponent(
                casterId = playerId,
                resolvingSpellEffectOverride = MayEffect(Effects.GainLife(1)),
            ),
        )
        if (copy) {
            spell = spell.with(
                CopyOfComponent(
                    originalCardDefinitionId = "original-spell",
                    copiedCardDefinitionId = "copied-spell",
                )
            )
        }

        val stamp = 101L
        val state = GameState(
            activePlayerId = playerId,
            phase = Phase.PRECOMBAT_MAIN,
            priorityPlayerId = playerId,
            step = Step.PRECOMBAT_MAIN,
            turnOrder = listOf(playerId, opponentId),
        )
            .withEntity(playerId, ComponentContainer.of(PlayerComponent("P1"), LifeTotalComponent(20)))
            .withEntity(opponentId, ComponentContainer.of(PlayerComponent("P2"), LifeTotalComponent(20)))
            .withEntity(spellId, spell)
            .copy(
                stack = listOf(spellId),
                objectIdentityStamps = mapOf(spellId to stamp),
                nextObjectIdentityStamp = stamp + 1L,
            )
        return Fixture(state, playerId, spellId, stamp)
    }

    fun resolvePaused(fixture: Fixture) =
        StackResolver(CardRegistry()).resolveTop(fixture.state)

    fun assertPauseEnvelope(result: com.wingedsheep.engine.core.ExecutionResult) {
        result.error shouldBe null
        result.isPaused shouldBe true
        val decision = result.pendingDecision.shouldNotBeNull()
        result.state.pendingDecision shouldBe decision
        result.state.peekContinuation().shouldNotBeNull()
        result.state.stack shouldBe emptyList()
        val zoneChange = result.events.filterIsInstance<ZoneChangeEvent>().single()
        zoneChange.fromZone shouldBe null
        zoneChange.toZone shouldBe Zone.GRAVEYARD
        result.events.filterIsInstance<ResolvedEvent>().size shouldBe 1
        result.events.map { it::class.simpleName } shouldBe
            listOf("DecisionRequestedEvent", "ZoneChangeEvent", "ResolvedEvent")
    }

    fun resumeYes(result: com.wingedsheep.engine.core.ExecutionResult): com.wingedsheep.engine.core.ExecutionResult {
        val decision = result.pendingDecision.shouldNotBeNull().shouldBeInstanceOf<YesNoDecision>()
        val services = EngineServices(CardRegistry())
        return services.continuationHandler.resume(
            result.state.clearPendingDecision(),
            YesNoResponse(decision.id, choice = true),
        )
    }

    fun submitYes(
        result: com.wingedsheep.engine.core.ExecutionResult,
        playerId: EntityId,
    ): com.wingedsheep.engine.core.ExecutionResult {
        val decision = result.pendingDecision.shouldNotBeNull().shouldBeInstanceOf<YesNoDecision>()
        return ActionProcessor(EngineServices(CardRegistry())).process(
            result.state,
            SubmitDecision(playerId, YesNoResponse(decision.id, choice = true)),
        ).result
    }

    test("ordinary paused spell has already left the stack before its decision is answered") {
        val fixture = fixture(copy = false)
        val result = resolvePaused(fixture)

        assertPauseEnvelope(result)
        result.state.hasEntity(fixture.spellId) shouldBe true
        result.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe true
        result.state.getEntity(fixture.spellId)?.has<SpellOnStackComponent>() shouldBe false
        result.state.objectIdentityStamps[fixture.spellId] shouldBe 102L
    }

    test("paused spell copy is already in its destination before its decision is answered") {
        val fixture = fixture(copy = true)
        val result = resolvePaused(fixture)

        assertPauseEnvelope(result)
        result.state.hasEntity(fixture.spellId) shouldBe true
        result.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe true
        result.state.getEntity(fixture.spellId)?.has<SpellOnStackComponent>() shouldBe false
        result.state.getEntity(fixture.spellId)?.has<CopyOfComponent>() shouldBe true
        result.state.objectIdentityStamps[fixture.spellId] shouldBe 102L
    }

    test("answering an ordinary paused spell does not create a later disposition event") {
        val fixture = fixture(copy = false)
        val paused = resolvePaused(fixture)
        assertPauseEnvelope(paused)

        val resumed = resumeYes(paused)

        resumed.error shouldBe null
        resumed.isPaused shouldBe false
        resumed.state.pendingDecision shouldBe null
        resumed.events.filterIsInstance<ZoneChangeEvent>() shouldBe emptyList()
        resumed.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe true
        resumed.state.objectIdentityStamps[fixture.spellId] shouldBe 102L
    }

    test("answering a paused spell copy does not create a later disposition event") {
        val fixture = fixture(copy = true)
        val paused = resolvePaused(fixture)
        assertPauseEnvelope(paused)

        val resumed = resumeYes(paused)

        resumed.error shouldBe null
        resumed.isPaused shouldBe false
        resumed.state.pendingDecision shouldBe null
        resumed.events.filterIsInstance<ZoneChangeEvent>() shouldBe emptyList()
        resumed.state.hasEntity(fixture.spellId) shouldBe true
        resumed.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe true
        resumed.state.objectIdentityStamps[fixture.spellId] shouldBe 102L
    }

    test("publicly answering a paused spell copy runs copy cleanup only after resolution completes") {
        val fixture = fixture(copy = true)
        val paused = resolvePaused(fixture)
        assertPauseEnvelope(paused)

        val resumed = submitYes(paused, fixture.playerId)

        resumed.error shouldBe null
        resumed.isPaused shouldBe false
        resumed.state.pendingDecision shouldBe null
        resumed.state.getEntity(fixture.spellId) shouldBe null
        PhantomCardCopiesCheck().check(resumed.state).newState.hasEntity(fixture.spellId) shouldBe false
        resumed.state.continuationStack shouldBe emptyList()
        resumed.state.hasEntity(fixture.spellId) shouldBe false
        resumed.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe false
        resumed.events.filterIsInstance<ZoneChangeEvent>() shouldBe emptyList()
    }
})
