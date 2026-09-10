package com.wingedsheep.engine.event

import com.wingedsheep.engine.core.ActionProcessor
import com.wingedsheep.engine.core.AlternativeCostType
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.ResolvedEvent
import com.wingedsheep.engine.core.SpellResolutionContinuation
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.handlers.ContinuationHandler
import com.wingedsheep.engine.mechanics.stack.StackResolver
import com.wingedsheep.engine.mechanics.sba.zone.PhantomCardCopiesCheck
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.CommanderComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.CopyOfComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.battlefield.ReplacementEffectSourceComponent
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.GraveyardCardsHaveFlashback
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.RedirectZoneChange
import com.wingedsheep.sdk.scripting.effects.MayEffect
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Regression coverage for the state boundary when an instant/sorcery resolution pauses for input.
 *
 * A pending effect decision is still part of spell resolution. The resolving object must remain
 * available until the continuation chain completes, at which point normal disposition and the
 * existing phantom-copy SBA run.
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

    fun withRedirect(fixture: Fixture, destination: Zone): GameState {
        val redirectSourceId = EntityId.generate()
        val redirect = RedirectZoneChange(
            newDestination = destination,
            appliesTo = EventPattern.ZoneChangeEvent(
                filter = GameObjectFilter.Any,
                from = Zone.STACK,
                to = Zone.GRAVEYARD,
            ),
        )
        val redirectSource = ComponentContainer.of(
            CardComponent(
                cardDefinitionId = "paused-spell-resolution-replacement",
                name = "Paused Spell Resolution Replacement",
                manaCost = ManaCost.ZERO,
                typeLine = TypeLine(cardTypes = setOf(CardType.ENCHANTMENT)),
                oracleText = "",
                ownerId = fixture.playerId,
            ),
            OwnerComponent(fixture.playerId),
            ControllerComponent(fixture.playerId),
            ReplacementEffectSourceComponent(listOf(redirect)),
        )
        return fixture.state
            .withEntity(redirectSourceId, redirectSource)
            .addToZone(ZoneKey(fixture.playerId, Zone.BATTLEFIELD), redirectSourceId)
    }

    fun withGraveyardRedirect(fixture: Fixture): GameState = withRedirect(fixture, Zone.EXILE)

    fun withHandRedirect(fixture: Fixture): GameState = withRedirect(fixture, Zone.HAND)

    fun resolvePaused(fixture: Fixture) =
        StackResolver(CardRegistry()).resolveTop(fixture.state)

    fun assertPauseEnvelope(result: com.wingedsheep.engine.core.ExecutionResult) {
        result.error shouldBe null
        result.isPaused shouldBe true
        val decision = result.pendingDecision.shouldNotBeNull()
        result.state.pendingDecision shouldBe decision
        result.state.peekContinuation().shouldNotBeNull()
        result.events.filterIsInstance<ZoneChangeEvent>() shouldBe emptyList()
        result.events.filterIsInstance<ResolvedEvent>() shouldBe emptyList()
        result.events.map { it::class.simpleName } shouldBe listOf("DecisionRequestedEvent")
    }

    fun assertFinalDisposition(
        result: com.wingedsheep.engine.core.ExecutionResult,
        destination: Zone = Zone.GRAVEYARD,
    ) {
        result.error shouldBe null
        result.isPaused shouldBe false
        result.state.pendingDecision shouldBe null
        result.state.continuationStack shouldBe emptyList()
        val zoneChange = result.events.filterIsInstance<ZoneChangeEvent>().single()
        zoneChange.toZone shouldBe destination
        val zoneIndex = result.events.indexOf(zoneChange)
        val resolvedIndex = result.events.indexOfFirst { it is ResolvedEvent }
        resolvedIndex shouldBe (zoneIndex + 1)
    }

    fun resumeDecision(
        result: com.wingedsheep.engine.core.ExecutionResult,
        choice: Boolean,
    ): com.wingedsheep.engine.core.ExecutionResult {
        val decision = result.pendingDecision.shouldNotBeNull().shouldBeInstanceOf<YesNoDecision>()
        val services = EngineServices(CardRegistry())
        return services.continuationHandler.resume(
            result.state.clearPendingDecision(),
            YesNoResponse(decision.id, choice),
        )
    }

    fun resumeYes(result: com.wingedsheep.engine.core.ExecutionResult): com.wingedsheep.engine.core.ExecutionResult {
        return resumeDecision(result, choice = true)
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

    test("ordinary paused spell stays in-flight until its decision is answered") {
        val fixture = fixture(copy = false)
        val result = resolvePaused(fixture)

        assertPauseEnvelope(result)
        result.state.hasEntity(fixture.spellId) shouldBe true
        result.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe false
        result.state.getEntity(fixture.spellId)?.has<SpellOnStackComponent>() shouldBe true
        result.state.objectIdentityStamps[fixture.spellId] shouldBe fixture.stamp
    }

    test("paused spell copy stays in-flight until its decision is answered") {
        val fixture = fixture(copy = true)
        val result = resolvePaused(fixture)

        assertPauseEnvelope(result)
        result.state.hasEntity(fixture.spellId) shouldBe true
        result.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe false
        result.state.getEntity(fixture.spellId)?.has<SpellOnStackComponent>() shouldBe true
        result.state.getEntity(fixture.spellId)?.has<CopyOfComponent>() shouldBe true
        result.state.objectIdentityStamps[fixture.spellId] shouldBe fixture.stamp
    }

    test("answering an ordinary paused spell disposes it after the effect completes") {
        val fixture = fixture(copy = false)
        val paused = resolvePaused(fixture)
        assertPauseEnvelope(paused)

        val resumed = resumeYes(paused)

        assertFinalDisposition(resumed)
        resumed.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe true
        resumed.state.getEntity(fixture.spellId)?.has<SpellOnStackComponent>() shouldBe false
        resumed.state.objectIdentityStamps[fixture.spellId] shouldBe 102L
    }

    test("answering a paused spell copy disposes it before the phantom-copy SBA") {
        val fixture = fixture(copy = true)
        val paused = resolvePaused(fixture)
        assertPauseEnvelope(paused)

        val resumed = resumeYes(paused)

        assertFinalDisposition(resumed)
        resumed.state.hasEntity(fixture.spellId) shouldBe true
        resumed.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe true
        resumed.state.getEntity(fixture.spellId)?.has<SpellOnStackComponent>() shouldBe false
        resumed.state.getEntity(fixture.spellId)?.has<CopyOfComponent>() shouldBe true
        resumed.state.objectIdentityStamps[fixture.spellId] shouldBe 102L
    }

    test("publicly answering a paused spell copy runs copy cleanup after resolution completes") {
        val fixture = fixture(copy = true)
        val paused = resolvePaused(fixture)
        assertPauseEnvelope(paused)

        val resumed = submitYes(paused, fixture.playerId)

        resumed.error shouldBe null
        resumed.isPaused shouldBe false
        resumed.state.pendingDecision shouldBe null
        resumed.state.getEntity(fixture.spellId) shouldBe null
        resumed.state.continuationStack shouldBe emptyList()
        resumed.state.hasEntity(fixture.spellId) shouldBe false
        resumed.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe false
        resumed.events.filterIsInstance<ZoneChangeEvent>().size shouldBe 1
        resumed.events.filterIsInstance<ResolvedEvent>().size shouldBe 1
        PhantomCardCopiesCheck().check(resumed.state).newState.hasEntity(fixture.spellId) shouldBe false
    }

    test("paused disposition applies a replacement after the effect decision completes") {
        val fixture = fixture(copy = false)
        val paused = resolvePaused(fixture.copy(state = withGraveyardRedirect(fixture)))

        assertPauseEnvelope(paused)
        paused.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe false
        paused.state.getZone(ZoneKey(fixture.playerId, Zone.EXILE)).contains(fixture.spellId) shouldBe false

        val resumed = resumeYes(paused)

        assertFinalDisposition(resumed, destination = Zone.EXILE)
        resumed.state.getZone(ZoneKey(fixture.playerId, Zone.EXILE)).contains(fixture.spellId) shouldBe true
        resumed.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe false
    }

    test("paused final disposition waits for a replacement decision before moving the spell") {
        val fixture = fixture(copy = false)
        val state = withHandRedirect(fixture)
            .copy(format = Format.Commander())
            .updateEntity(fixture.spellId) {
                it.with(CommanderComponent(ownerId = fixture.playerId))
            }
        val paused = resolvePaused(fixture.copy(state = state))

        assertPauseEnvelope(paused)
        val afterEffect = resumeYes(paused)

        afterEffect.error shouldBe null
        afterEffect.isPaused shouldBe true
        afterEffect.pendingDecision.shouldNotBeNull().shouldBeInstanceOf<YesNoDecision>()
        afterEffect.state.continuationStack.filterIsInstance<SpellResolutionContinuation>()
            .single().dispositionPending shouldBe true
        afterEffect.state.getEntity(fixture.spellId)?.has<SpellOnStackComponent>() shouldBe true
        afterEffect.state.getZone(ZoneKey(fixture.playerId, Zone.HAND)).contains(fixture.spellId) shouldBe false
        afterEffect.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe false
        afterEffect.events.filterIsInstance<ZoneChangeEvent>() shouldBe emptyList()
        afterEffect.events.filterIsInstance<ResolvedEvent>() shouldBe emptyList()

        val final = resumeDecision(afterEffect, choice = false)

        assertFinalDisposition(final, destination = Zone.HAND)
        final.state.getZone(ZoneKey(fixture.playerId, Zone.HAND)).contains(fixture.spellId) shouldBe true
        final.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe false
    }

    test("cast-time flashback still exiles when its battlefield grant disappears during resolution") {
        val fixture = fixture(copy = false)
        val grantSourceId = EntityId.generate()
        val grantSourceName = "Paused Flashback Grant Source"
        val grantSourceDefinition = card(grantSourceName) {
            manaCost = "{2}"
            typeLine = "Enchantment"
            staticAbility {
                ability = GraveyardCardsHaveFlashback(GameObjectFilter.Any)
            }
        }
        val registry = CardRegistry().also { it.register(grantSourceDefinition) }
        val grantSource = ComponentContainer.of(
            CardComponent(
                cardDefinitionId = grantSourceName,
                name = grantSourceName,
                manaCost = ManaCost.ZERO,
                typeLine = TypeLine(cardTypes = setOf(CardType.ENCHANTMENT)),
                oracleText = "",
                ownerId = fixture.playerId,
            ),
            OwnerComponent(fixture.playerId),
            ControllerComponent(fixture.playerId),
        )
        val spell = fixture.state.getEntity(fixture.spellId)
            .shouldNotBeNull()
            .get<SpellOnStackComponent>()
            .shouldNotBeNull()
        val state = fixture.state
            .withEntity(grantSourceId, grantSource)
            .addToZone(ZoneKey(fixture.playerId, Zone.BATTLEFIELD), grantSourceId)
            .updateEntity(fixture.spellId) {
                it.with(
                    spell.copy(
                        castFromZone = Zone.GRAVEYARD,
                        alternativeCost = AlternativeCostType.FLASHBACK,
                        resolvingSpellEffectOverride = Effects.Composite(
                            Effects.Destroy(EffectTarget.SpecificEntity(grantSourceId)),
                            MayEffect(Effects.GainLife(1)),
                        ),
                    )
                )
            }

        val paused = StackResolver(registry).resolveTop(state)
        paused.error shouldBe null
        paused.isPaused shouldBe true
        paused.pendingDecision.shouldNotBeNull().shouldBeInstanceOf<YesNoDecision>()
        paused.state.pendingDecision shouldBe paused.pendingDecision
        paused.state.getBattlefield(fixture.playerId).contains(grantSourceId) shouldBe false
        paused.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe false
        paused.events.filterIsInstance<ZoneChangeEvent>().map { it.entityId } shouldBe listOf(grantSourceId)
        paused.events.filterIsInstance<ResolvedEvent>() shouldBe emptyList()

        val resumed = resumeYes(paused)

        resumed.error shouldBe null
        resumed.state.getZone(ZoneKey(fixture.playerId, Zone.EXILE)).contains(fixture.spellId) shouldBe true
        resumed.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe false
    }

    test("legacy alternative-cost casts freeze the selected flashback provenance on the stack") {
        val grantSourceName = "Legacy Flashback Grant Source"
        val spellName = "Legacy Flashback Spell"
        val grantSource = card(grantSourceName) {
            manaCost = "{2}"
            typeLine = "Enchantment"
            staticAbility {
                ability = GraveyardCardsHaveFlashback(GameObjectFilter.Any)
            }
        }
        val spell = card(spellName) {
            manaCost = "{0}"
            typeLine = "Instant"
            spell {
                effect = Effects.Composite(
                    Effects.DestroyAll(GameObjectFilter.Enchantment),
                    MayEffect(Effects.GainLife(1)),
                )
            }
        }
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + listOf(grantSource, spell))
        driver.initMirrorMatch(
            deck = Deck.of("Mountain" to 40),
            skipMulligans = true,
            startingLife = 20,
        )
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val player = driver.activePlayer!!
        val grantSourceId = driver.putPermanentOnBattlefield(player, grantSourceName)
        val spellId = driver.putCardInGraveyard(player, spellName)
        val cast = driver.submit(
            CastSpell(
                playerId = player,
                cardId = spellId,
                useAlternativeCost = true,
            )
        )
        cast.error shouldBe null
        val stackComponent = driver.state.getEntity(spellId)
            .shouldNotBeNull()
            .get<SpellOnStackComponent>()
            .shouldNotBeNull()
        stackComponent.alternativeCost shouldBe AlternativeCostType.FLASHBACK

        val paused = driver.bothPass()
        paused.isPaused shouldBe true
        driver.state.getBattlefield(player).contains(grantSourceId) shouldBe false
        driver.state.getZone(ZoneKey(player, Zone.GRAVEYARD)).contains(spellId) shouldBe false

        val resolved = driver.submitYesNo(player, choice = true)

        resolved.error shouldBe null
        driver.state.getZone(ZoneKey(player, Zone.EXILE)).contains(spellId) shouldBe true
        driver.state.getZone(ZoneKey(player, Zone.GRAVEYARD)).contains(spellId) shouldBe false
    }

    test("legacy alternative-cost casts freeze the selected harmonize provenance on the stack") {
        val spellName = "Legacy Harmonize Spell"
        val spell = card(spellName) {
            manaCost = "{0}"
            typeLine = "Instant"
            spell {
                effect = MayEffect(Effects.GainLife(1))
            }
            keywordAbility(KeywordAbility.harmonize("{0}"))
        }
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + spell)
        driver.initMirrorMatch(
            deck = Deck.of("Mountain" to 40),
            skipMulligans = true,
            startingLife = 20,
        )
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val player = driver.activePlayer!!
        val spellId = driver.putCardInGraveyard(player, spellName)
        val cast = driver.submit(
            CastSpell(
                playerId = player,
                cardId = spellId,
                useAlternativeCost = true,
            )
        )
        cast.error shouldBe null
        driver.state.getEntity(spellId)
            ?.get<SpellOnStackComponent>()
            ?.alternativeCost shouldBe AlternativeCostType.HARMONIZE

        val paused = driver.bothPass()
        paused.isPaused shouldBe true
        val resolved = driver.submitYesNo(player, choice = true)

        resolved.error shouldBe null
        driver.state.getZone(ZoneKey(player, Zone.EXILE)).contains(spellId) shouldBe true
        driver.state.getZone(ZoneKey(player, Zone.GRAVEYARD)).contains(spellId) shouldBe false
    }
})
