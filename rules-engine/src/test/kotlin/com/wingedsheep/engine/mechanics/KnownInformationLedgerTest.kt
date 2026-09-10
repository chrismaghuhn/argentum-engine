package com.wingedsheep.engine.mechanics

import com.wingedsheep.engine.core.CardsRevealedEvent
import com.wingedsheep.engine.core.CardsDrawnEvent
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.HandLookedAtEvent
import com.wingedsheep.engine.core.LibrarySearchedEvent
import com.wingedsheep.engine.core.LibraryShuffledEvent
import com.wingedsheep.engine.core.LookedAtCardsEvent
import com.wingedsheep.engine.core.TurnedFaceDownEvent
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.core.SpellCastEvent
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.PipelineState
import com.wingedsheep.engine.handlers.effects.library.GatherCardsExecutor
import com.wingedsheep.engine.handlers.effects.library.LibraryRevealUtils
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.state.components.player.KnownInformationAcquisitionReason
import com.wingedsheep.engine.state.components.player.KnownInformationAudience
import com.wingedsheep.engine.state.components.player.KnownInformationFactKind
import com.wingedsheep.engine.state.components.player.KnownInformationFactV1
import com.wingedsheep.engine.state.components.player.KnownInformationLedgerComponentV1
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.effects.CardSource
import com.wingedsheep.sdk.scripting.effects.GatherCardsEffect
import com.wingedsheep.sdk.scripting.effects.LookAudience
import com.wingedsheep.sdk.scripting.references.Player
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Rules-state contract tests for the perspective-scoped known-information ledger. */
class KnownInformationLedgerTest : FunSpec({
    val p1 = EntityId.of("p1")
    val p2 = EntityId.of("p2")
    val registry = CardRegistry()

    data class CardSpec(
        val id: EntityId,
        val owner: EntityId,
        val zone: Zone,
        val definition: String = id.value,
    )

    fun card(spec: CardSpec): ComponentContainer = ComponentContainer.of(
        CardComponent(
            cardDefinitionId = spec.definition,
            name = spec.definition,
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            ownerId = spec.owner,
        ),
        OwnerComponent(spec.owner),
    )

    fun stateWith(vararg cards: CardSpec): GameState {
        var state = GameState(
            turnOrder = listOf(p1, p2),
            activePlayerId = p1,
            priorityPlayerId = p1,
        )
            .withEntity(p1, ComponentContainer.EMPTY)
            .withEntity(p2, ComponentContainer.EMPTY)
        for (spec in cards) {
            state = state
                .withEntity(spec.id, card(spec))
                .addToZone(ZoneKey(spec.owner, spec.zone), spec.id)
        }
        return state
    }

    fun apply(before: GameState, result: ExecutionResult): GameState =
        KnownInformationLedger.applyAfterAction(before, result, registry).state

    fun facts(state: GameState, perspective: EntityId): List<KnownInformationFactV1> =
        KnownInformationLedger.forPlayer(state, perspective).activeFacts

    fun fact(
        state: GameState,
        perspective: EntityId,
        subject: EntityId,
        kind: KnownInformationFactKind,
    ): KnownInformationFactV1 = facts(state, perspective).single {
        it.subjectEntityId == subject && it.factKind == kind
    }

    fun publicReveal(state: GameState, cardId: EntityId): GameState = apply(
        state,
        ExecutionResult.success(
            state,
            listOf(
                CardsRevealedEvent(
                    revealingPlayerId = p1,
                    cardIds = listOf(cardId),
                    cardNames = listOf("public-card"),
                )
            ),
        ),
    )

    fun stateJson(): Json = Json {
        serializersModule = engineSerializersModule
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = false
        allowStructuredMapKeys = true
    }

    test("HISTB-01 public reveal records identity and zone facts for every player") {
        val cardId = EntityId.of("public-card")
        val state = publicReveal(stateWith(CardSpec(cardId, p1, Zone.HAND)), cardId)

        facts(state, p1).count { it.subjectEntityId == cardId } shouldBe 2
        facts(state, p2).count { it.subjectEntityId == cardId } shouldBe 2
        fact(state, p1, cardId, KnownInformationFactKind.IDENTITY).audience shouldBe
            KnownInformationAudience.PUBLIC
        KnownInformationLedger.forPlayer(state, p1).knowledgeEpoch shouldBe 1L
        KnownInformationLedger.forPlayer(state, p2).knowledgeEpoch shouldBe 1L
    }

    test("HISTB-02 revealToSelf is UI-only and does not remove public knowledge") {
        val cardId = EntityId.of("reveal-overlay-hidden")
        val state = stateWith(CardSpec(cardId, p1, Zone.HAND))
        val result = ExecutionResult.success(
            state,
            listOf(
                CardsRevealedEvent(
                    revealingPlayerId = p1,
                    cardIds = listOf(cardId),
                    cardNames = listOf("revealed"),
                    revealToSelf = false,
                )
            ),
        )

        val after = apply(state, result)
        fact(after, p1, cardId, KnownInformationFactKind.IDENTITY).audience shouldBe
            KnownInformationAudience.PUBLIC
        fact(after, p2, cardId, KnownInformationFactKind.IDENTITY).acquisitionReason shouldBe
            KnownInformationAcquisitionReason.PUBLIC_REVEAL
    }

    test("HISTB-03 private hand look changes only the viewing perspective") {
        val cardId = EntityId.of("private-hand")
        val state = stateWith(CardSpec(cardId, p1, Zone.HAND))
        val looked = LibraryRevealUtils.markRevealed(
            state = state,
            cardIds = listOf(cardId),
            playerIds = listOf(p2),
            audience = KnownInformationAudience.PERSPECTIVE_PRIVATE,
            acquisitionReason = KnownInformationAcquisitionReason.PRIVATE_HAND_LOOK,
        )
        val after = apply(
            state,
            ExecutionResult.success(looked, listOf(HandLookedAtEvent(p2, p1, listOf(cardId)))),
        )

        facts(after, p1).shouldBeEmpty()
        fact(after, p2, cardId, KnownInformationFactKind.IDENTITY).audience shouldBe
            KnownInformationAudience.PERSPECTIVE_PRIVATE
        KnownInformationLedger.forPlayer(after, p1).knowledgeEpoch shouldBe 0L
        KnownInformationLedger.forPlayer(after, p2).knowledgeEpoch shouldBe 1L
    }

    test("HISTB-04 private card look records identity only for the authorized viewer") {
        val cardId = EntityId.of("face-down-card")
        val state = stateWith(CardSpec(cardId, p1, Zone.BATTLEFIELD))
            .updateEntity(cardId) { it.with(FaceDownComponent) }
        val looked = LibraryRevealUtils.markRevealed(
            state = state,
            cardIds = listOf(cardId),
            playerIds = listOf(p2),
            audience = KnownInformationAudience.PERSPECTIVE_PRIVATE,
            acquisitionReason = KnownInformationAcquisitionReason.PRIVATE_CARD_LOOK,
        )
        val after = apply(
            state,
            ExecutionResult.success(looked, listOf(LookedAtCardsEvent(p2, listOf(cardId)))),
        )

        facts(after, p1).shouldBeEmpty()
        fact(after, p2, cardId, KnownInformationFactKind.IDENTITY).acquisitionReason shouldBe
            KnownInformationAcquisitionReason.PRIVATE_CARD_LOOK
    }

    test("HISTB-18 same-name hand ambiguity invalidates the ledger identity facts") {
        val castCard = EntityId.of("same-name-cast")
        val twinCard = EntityId.of("same-name-twin")
        val initial = stateWith(
            CardSpec(castCard, p2, Zone.HAND, "Ambiguous Card"),
            CardSpec(twinCard, p2, Zone.HAND, "Ambiguous Card"),
        )
        val revealEvent = CardsRevealedEvent(
            revealingPlayerId = p2,
            cardIds = listOf(castCard, twinCard),
            cardNames = listOf("Ambiguous Card", "Ambiguous Card"),
        )
        val revealed = RevealedInHandTracker.applyAfterAction(
            ExecutionResult.success(initial, listOf(revealEvent))
        )
        val known = apply(initial, revealed)
        val onStack = known
            .removeFromZone(ZoneKey(p2, Zone.HAND), castCard)
            .pushToStack(castCard)
            .updateEntity(castCard) {
                it.with(SpellOnStackComponent(casterId = p2, castFromZone = Zone.HAND))
            }
        val tracked = RevealedInHandTracker.applyAfterAction(
            ExecutionResult.success(
                onStack,
                listOf(SpellCastEvent(castCard, "Ambiguous Card", p2)),
            )
        )
        val after = KnownInformationLedger.applyAfterAction(
            beforeState = known,
            result = tracked,
            cardRegistry = registry,
        ).state

        facts(after, p1).none {
            it.subjectEntityId == twinCard && it.factKind == KnownInformationFactKind.IDENTITY
        } shouldBe true
        facts(after, p2).none {
            it.subjectEntityId == twinCard && it.factKind == KnownInformationFactKind.IDENTITY
        } shouldBe true
    }

    test("HISTB-REVIEW-19 library reorder advances the Rules object incarnation") {
        val cardId = EntityId.of("reordered-object")
        val initial = stateWith(CardSpec(cardId, p1, Zone.LIBRARY))
        val oldStamp = initial.objectIdentityStamps[cardId]

        val reordered = KnownInformationLedger.recordLibraryOrder(
            state = initial,
            perspectivePlayerId = p1,
            orderedCardIds = listOf(cardId),
        )

        reordered.objectIdentityStamps[cardId] shouldNotBe oldStamp
    }

    test("HISTB-REVIEW-20 library zone entry advances the Rules object incarnation") {
        val cardId = EntityId.of("library-entry-object")
        val initial = stateWith(CardSpec(cardId, p1, Zone.HAND))
        val oldStamp = initial.objectIdentityStamps[cardId]

        val moved = com.wingedsheep.engine.handlers.effects.ZoneTransitionService.moveToZone(
            state = initial,
            entityId = cardId,
            destinationZone = Zone.LIBRARY,
            fromZoneKey = ZoneKey(p1, Zone.HAND),
        ).state

        moved.objectIdentityStamps[cardId] shouldNotBe oldStamp
    }

    test("HISTB-REVIEW-24 library entry invalidates other cards' stale positions") {
        val first = EntityId.of("membership-first")
        val second = EntityId.of("membership-second")
        val entering = EntityId.of("membership-entering")
        val initial = stateWith(
            CardSpec(first, p1, Zone.LIBRARY),
            CardSpec(second, p1, Zone.LIBRARY),
            CardSpec(entering, p1, Zone.HAND),
        )
        val known = apply(
            initial,
            ExecutionResult.success(
                KnownInformationLedger.recordLibraryOrder(initial, p1, listOf(first, second)),
            ),
        )
        val moved = com.wingedsheep.engine.handlers.effects.ZoneTransitionService.moveToZone(
            state = known,
            entityId = entering,
            destinationZone = Zone.LIBRARY,
            fromZoneKey = ZoneKey(p1, Zone.HAND),
        )
        val after = apply(known, ExecutionResult.success(moved.state, moved.events))

        facts(after, p1).none {
            it.factKind == KnownInformationFactKind.POSITION_OR_ORDER &&
                it.subjectEntityId in setOf(first, second)
        } shouldBe true
    }

    test("HISTB-REVIEW-25 library exit invalidates remaining cards' stale positions") {
        val first = EntityId.of("exit-first")
        val second = EntityId.of("exit-second")
        val initial = stateWith(
            CardSpec(first, p1, Zone.LIBRARY),
            CardSpec(second, p1, Zone.LIBRARY),
        )
        val known = apply(
            initial,
            ExecutionResult.success(
                KnownInformationLedger.recordLibraryOrder(initial, p1, listOf(first, second)),
            ),
        )
        val moved = com.wingedsheep.engine.handlers.effects.ZoneTransitionService.moveToZone(
            state = known,
            entityId = first,
            destinationZone = Zone.HAND,
            fromZoneKey = ZoneKey(p1, Zone.LIBRARY),
        )
        val after = apply(known, ExecutionResult.success(moved.state, moved.events))

        facts(after, p1).none {
            it.subjectEntityId == second && it.factKind == KnownInformationFactKind.POSITION_OR_ORDER
        } shouldBe true
    }

    test("HISTB-05 private search records searched library cards but not to the opponent") {
        val first = EntityId.of("search-card-a")
        val second = EntityId.of("search-card-b")
        val state = stateWith(
            CardSpec(first, p1, Zone.LIBRARY, "Card A"),
            CardSpec(second, p1, Zone.LIBRARY, "Card B"),
        )
        val gather = GatherCardsExecutor().execute(
            state = state,
            effect = GatherCardsEffect(
                source = CardSource.FromZone(Zone.LIBRARY, Player.You, GameObjectFilter.Any),
                storeAs = "searchable",
                lookAudience = LookAudience.Controller,
            ),
            context = EffectContext(sourceId = null, controllerId = p1),
        )
        val after = apply(
            state,
            ExecutionResult.success(
                LibraryRevealUtils.clearLibraryReveals(gather.state, p1),
                listOf(LibraryShuffledEvent(p1), LibrarySearchedEvent(p1, "Search")),
            ),
        )

        listOf(first, second).forEach { id ->
            fact(after, p1, id, KnownInformationFactKind.IDENTITY).acquisitionReason shouldBe
                KnownInformationAcquisitionReason.PRIVATE_SEARCH
            facts(after, p2).none { it.subjectEntityId == id } shouldBe true
            facts(after, p1).none {
                it.subjectEntityId == id && it.factKind == KnownInformationFactKind.POSITION_OR_ORDER
            } shouldBe true
        }
    }

    test("HISTB-06 reveal collection writes durable visibility metadata through the Rules seam") {
        val cardId = EntityId.of("reveal-only")
        val state = stateWith(CardSpec(cardId, p1, Zone.LIBRARY))
        val result = com.wingedsheep.engine.handlers.effects.library.RevealCollectionExecutor().execute(
            state = state,
            effect = com.wingedsheep.sdk.scripting.effects.RevealCollectionEffect(from = "cards"),
            context = EffectContext(
                sourceId = null,
                controllerId = p1,
                pipeline = PipelineState(storedCollections = mapOf("cards" to listOf(cardId))),
            ),
        )

        result.state.getEntity(cardId)
            ?.get<com.wingedsheep.engine.state.components.identity.RevealedToComponent>()
            ?.playerIds shouldBe setOf(p1, p2)
        fact(result.state, p1, cardId, KnownInformationFactKind.IDENTITY).audience shouldBe
            KnownInformationAudience.PUBLIC
    }

    test("HISTB-07 shuffle invalidates position/order but preserves identity and membership") {
        val cardId = EntityId.of("known-library-card")
        val initial = stateWith(CardSpec(cardId, p1, Zone.LIBRARY))
        val marked = KnownInformationLedger.recordLibraryOrder(
            state = initial,
            perspectivePlayerId = p1,
            orderedCardIds = listOf(cardId),
        )
        val known = apply(initial, ExecutionResult.success(marked))
        val after = apply(known, ExecutionResult.success(known, listOf(LibraryShuffledEvent(p1))))

        fact(after, p1, cardId, KnownInformationFactKind.IDENTITY).knownZone shouldBe Zone.LIBRARY
        fact(after, p1, cardId, KnownInformationFactKind.ZONE_MEMBERSHIP).knownZone shouldBe Zone.LIBRARY
        facts(after, p1).none { it.factKind == KnownInformationFactKind.POSITION_OR_ORDER } shouldBe true
        KnownInformationLedger.forPlayer(after, p1).knowledgeEpoch shouldBe 2L
    }

    test("HISTB-08 shuffle without known order does not create a false epoch bump") {
        val cardId = EntityId.of("known-library-card")
        val initial = stateWith(CardSpec(cardId, p1, Zone.LIBRARY))
        val marked = KnownInformationLedger.recordLibraryOrder(
            state = initial,
            perspectivePlayerId = p1,
            orderedCardIds = listOf(cardId),
        )
        val known = apply(initial, ExecutionResult.success(marked))
        val shuffled = apply(known, ExecutionResult.success(known, listOf(LibraryShuffledEvent(p1))))
        val shuffledAgain = apply(shuffled, ExecutionResult.success(shuffled, listOf(LibraryShuffledEvent(p1))))

        KnownInformationLedger.forPlayer(shuffled, p1).knowledgeEpoch shouldBe 2L
        KnownInformationLedger.forPlayer(shuffledAgain, p1).knowledgeEpoch shouldBe 2L
    }

    test("HISTB-08b revealing a library card does not imply its hidden position") {
        val cardId = EntityId.of("revealed-library-card")
        val otherId = EntityId.of("other-library-card")
        val state = stateWith(
            CardSpec(cardId, p1, Zone.LIBRARY),
            CardSpec(otherId, p1, Zone.LIBRARY),
        )

        val after = publicReveal(state, cardId)

        fact(after, p1, cardId, KnownInformationFactKind.IDENTITY).knownZone shouldBe Zone.LIBRARY
        facts(after, p1).none {
            it.subjectEntityId == cardId && it.factKind == KnownInformationFactKind.POSITION_OR_ORDER
        } shouldBe true
    }

    test("HISTB-09 exact producer-owned reorder updates only the chooser's positions") {
        val first = EntityId.of("order-first")
        val second = EntityId.of("order-second")
        val initial = stateWith(
            CardSpec(first, p1, Zone.LIBRARY),
            CardSpec(second, p1, Zone.LIBRARY),
        )
        val initialKnown = apply(
            initial,
            ExecutionResult.success(
                KnownInformationLedger.recordLibraryOrder(initial, p1, listOf(first, second)),
            ),
        )
        val reordered = initialKnown.copy(
            zones = initialKnown.zones + (ZoneKey(p1, Zone.LIBRARY) to listOf(second, first)),
        )
        val after = apply(
            initialKnown,
            ExecutionResult.success(
                KnownInformationLedger.recordLibraryOrder(reordered, p1, listOf(second, first)),
            ),
        )

        fact(after, p1, second, KnownInformationFactKind.POSITION_OR_ORDER).knownPosition shouldBe 0
        fact(after, p1, first, KnownInformationFactKind.POSITION_OR_ORDER).knownPosition shouldBe 1
        facts(after, p2).shouldBeEmpty()
        KnownInformationLedger.forPlayer(after, p1).knowledgeEpoch shouldBe 2L
    }

    test("HISTB-10 hidden opponent mutations do not change the acting perspective ledger") {
        val knownCard = EntityId.of("p1-known")
        val opponentA = EntityId.of("opponent-a")
        val opponentB = EntityId.of("opponent-b")
        val base = stateWith(
            CardSpec(knownCard, p1, Zone.LIBRARY),
            CardSpec(opponentA, p2, Zone.HAND, "Hidden A"),
        )
        val known = apply(
            base,
            ExecutionResult.success(
                LibraryRevealUtils.markRevealed(
                    state = base,
                    cardIds = listOf(knownCard),
                    playerIds = listOf(p1),
                    audience = KnownInformationAudience.PERSPECTIVE_PRIVATE,
                    acquisitionReason = KnownInformationAcquisitionReason.PRIVATE_LIBRARY_LOOK,
                ),
            ),
        )
        val hiddenOnly = known
            .withEntity(opponentB, card(CardSpec(opponentB, p2, Zone.HAND, "Hidden B")))
            .copy(
                zones = known.zones
                    .minus(ZoneKey(p2, Zone.HAND))
                    .plus(ZoneKey(p2, Zone.HAND) to listOf(opponentB)),
            )
        val after = apply(known, ExecutionResult.success(hiddenOnly))

        KnownInformationLedger.forPlayer(after, p1) shouldBe KnownInformationLedger.forPlayer(known, p1)
        KnownInformationLedger.forPlayer(after, p1).knowledgeEpoch shouldBe
            KnownInformationLedger.forPlayer(known, p1).knowledgeEpoch
    }

    test("HISTB-11 a later reveal does not mutate the immutable earlier ledger state") {
        val cardId = EntityId.of("future-reveal")
        val before = stateWith(CardSpec(cardId, p1, Zone.HAND))
        val beforeLedger = KnownInformationLedger.forPlayer(before, p1)
        val after = publicReveal(before, cardId)

        KnownInformationLedger.forPlayer(before, p1) shouldBe beforeLedger
        facts(after, p1).any { it.subjectEntityId == cardId } shouldBe true
        KnownInformationLedger.forPlayer(after, p1).knowledgeEpoch shouldBe 1L
    }

    test("HISTB-12 zone change uses a new incarnation and drops hidden old-object facts") {
        val cardId = EntityId.of("incarnation-card")
        val initial = stateWith(CardSpec(cardId, p1, Zone.LIBRARY))
        val known = apply(
            initial,
            ExecutionResult.success(
                LibraryRevealUtils.markRevealed(
                    state = initial,
                    cardIds = listOf(cardId),
                    playerIds = listOf(p1),
                    audience = KnownInformationAudience.PERSPECTIVE_PRIVATE,
                    acquisitionReason = KnownInformationAcquisitionReason.PRIVATE_LIBRARY_LOOK,
                ),
            ),
        )
        val movedResult = com.wingedsheep.engine.handlers.effects.ZoneTransitionService.moveToZone(
            state = known,
            entityId = cardId,
            destinationZone = Zone.HAND,
            fromZoneKey = ZoneKey(p1, Zone.LIBRARY),
        )
        val moved = movedResult.state
        val after = apply(
            known,
            ExecutionResult.success(
                moved,
                movedResult.events,
            ),
        )

        val oldStamp = known.objectIdentityStamps[cardId]
        val newStamp = moved.objectIdentityStamps[cardId]
        facts(after, p1).none {
            it.subjectEntityId == cardId && it.objectIdentityStamp == oldStamp
        } shouldBe true
        fact(after, p1, cardId, KnownInformationFactKind.IDENTITY).objectIdentityStamp shouldBe newStamp
        moved.getEntity(cardId)
            ?.get<com.wingedsheep.engine.state.components.identity.RevealedToComponent>() shouldBe null
        KnownInformationLedger.forPlayer(after, p1).knowledgeEpoch shouldBe 2L
        newStamp shouldBe (known.objectIdentityStamps[cardId]!! + 1L)
    }

    test("HISTB-12 public destination records the new visible incarnation") {
        val cardId = EntityId.of("public-incarnation")
        val initial = stateWith(CardSpec(cardId, p1, Zone.HAND))
        val moved = initial.moveToZone(
            cardId,
            ZoneKey(p1, Zone.HAND),
            ZoneKey(p1, Zone.BATTLEFIELD),
        )
        val after = apply(
            initial,
            ExecutionResult.success(
                moved,
                listOf(ZoneChangeEvent(cardId, "public-incarnation", Zone.HAND, Zone.BATTLEFIELD, p1)),
            ),
        )

        listOf(p1, p2).forEach { perspective ->
            fact(after, perspective, cardId, KnownInformationFactKind.IDENTITY).knownZone shouldBe
                Zone.BATTLEFIELD
            fact(after, perspective, cardId, KnownInformationFactKind.IDENTITY).objectIdentityStamp shouldBe
                moved.objectIdentityStamps[cardId]
        }
    }

    test("HISTB-12b a face-up spell entering the public stack is known to every perspective") {
        val cardId = EntityId.of("public-stack-spell")
        val initial = stateWith(CardSpec(cardId, p2, Zone.HAND))
        val onStack = initial
            .removeFromZone(ZoneKey(p2, Zone.HAND), cardId)
            .pushToStack(cardId)
            .updateEntity(cardId) { it.with(SpellOnStackComponent(casterId = p2)) }
        val after = apply(
            initial,
            ExecutionResult.success(
                onStack,
                listOf(ZoneChangeEvent(cardId, "Public Stack Spell", Zone.HAND, Zone.STACK, p2)),
            ),
        )

        listOf(p1, p2).forEach { perspective ->
            fact(after, perspective, cardId, KnownInformationFactKind.IDENTITY).audience shouldBe
                KnownInformationAudience.PUBLIC
            fact(after, perspective, cardId, KnownInformationFactKind.IDENTITY).knownZone shouldBe
                Zone.STACK
        }
    }

    test("HISTB-12c a face-down spell on the stack remains private to its caster") {
        val cardId = EntityId.of("private-stack-spell")
        val initial = stateWith(CardSpec(cardId, p2, Zone.HAND))
        val onStack = initial
            .removeFromZone(ZoneKey(p2, Zone.HAND), cardId)
            .pushToStack(cardId)
            .updateEntity(cardId) {
                it.with(SpellOnStackComponent(casterId = p2, castFaceDown = true))
            }
        val after = apply(
            initial,
            ExecutionResult.success(
                onStack,
                listOf(ZoneChangeEvent(cardId, "Private Stack Spell", Zone.HAND, Zone.STACK, p2)),
            ),
        )

        facts(after, p1).none { it.subjectEntityId == cardId } shouldBe true
        fact(after, p2, cardId, KnownInformationFactKind.IDENTITY).audience shouldBe
            KnownInformationAudience.PERSPECTIVE_PRIVATE
    }

    test("HISTB-13 an unauthorized face-down object never gains identity knowledge") {
        val cardId = EntityId.of("face-down-unknown")
        val before = stateWith(CardSpec(cardId, p1, Zone.BATTLEFIELD))
        val after = apply(
            before,
            ExecutionResult.success(
                before.updateEntity(cardId) { it.with(FaceDownComponent) },
                listOf(TurnedFaceDownEvent(cardId, p1)),
            ),
        )

        facts(after, p2).none { it.subjectEntityId == cardId } shouldBe true
    }

    test("HISTB-13b turning a known public object face down invalidates unauthorized identity") {
        val cardId = EntityId.of("known-face-down")
        val visible = publicReveal(stateWith(CardSpec(cardId, p2, Zone.BATTLEFIELD)), cardId)
        val facedDown = visible.updateEntity(cardId) { it.with(FaceDownComponent) }
        val after = apply(
            visible,
            ExecutionResult.success(facedDown, listOf(TurnedFaceDownEvent(cardId, p2))),
        )

        facts(after, p1).none {
            it.subjectEntityId == cardId && it.factKind == KnownInformationFactKind.IDENTITY
        } shouldBe true
        fact(after, p1, cardId, KnownInformationFactKind.ZONE_MEMBERSHIP).knownZone shouldBe
            Zone.BATTLEFIELD
    }

    test("HISTB-14 advancing a forked state cannot mutate the parent ledger") {
        val first = EntityId.of("fork-known")
        val second = EntityId.of("fork-private")
        val parent = apply(
            stateWith(
                CardSpec(first, p1, Zone.HAND),
                CardSpec(second, p2, Zone.HAND),
            ),
            ExecutionResult.success(
                LibraryRevealUtils.markRevealed(
                    state = stateWith(
                        CardSpec(first, p1, Zone.HAND),
                        CardSpec(second, p2, Zone.HAND),
                    ),
                    cardIds = listOf(first),
                    playerIds = listOf(p1),
                    audience = KnownInformationAudience.PERSPECTIVE_PRIVATE,
                    acquisitionReason = KnownInformationAcquisitionReason.PRIVATE_HAND_LOOK,
                ),
            ),
        )
        val fork = KnownInformationLedger.recordCards(
            state = parent,
            cardIds = listOf(second),
            perspectivePlayerIds = listOf(p2),
            audience = KnownInformationAudience.PERSPECTIVE_PRIVATE,
            acquisitionReason = KnownInformationAcquisitionReason.PRIVATE_HAND_LOOK,
        )

        facts(parent, p2).shouldBeEmpty()
        facts(fork, p2).any { it.subjectEntityId == second } shouldBe true
    }

    test("HISTB-15 a fresh episode starts without prior knowledge") {
        val cardId = EntityId.of("old-episode-card")
        val old = publicReveal(stateWith(CardSpec(cardId, p1, Zone.HAND)), cardId)
        val fresh = stateWith(CardSpec(cardId, p1, Zone.HAND))

        facts(old, p1).isNotEmpty() shouldBe true
        facts(fresh, p1).shouldBeEmpty()
        KnownInformationLedger.forPlayer(fresh, p1).knowledgeEpoch shouldBe 0L
    }

    test("HISTB-16 serialized state round-trips the immutable ledger") {
        val cardId = EntityId.of("serialized-card")
        val state = publicReveal(stateWith(CardSpec(cardId, p1, Zone.HAND)), cardId)
        val json = stateJson()
        val encoded = json.encodeToString(GameState.serializer(), state)
        val decoded = json.decodeFromString(GameState.serializer(), encoded)

        KnownInformationLedger.forPlayer(decoded, p1) shouldBe KnownInformationLedger.forPlayer(state, p1)
        KnownInformationLedger.forPlayer(decoded, p2) shouldBe KnownInformationLedger.forPlayer(state, p2)
    }

    test("HISTB-17 identical state and knowledge inputs evolve deterministically") {
        val cardId = EntityId.of("deterministic-card")
        fun evolve(): GameState {
            val initial = stateWith(CardSpec(cardId, p1, Zone.LIBRARY))
            val marked = LibraryRevealUtils.markRevealed(
                state = initial,
                cardIds = listOf(cardId),
                playerIds = listOf(p1),
                audience = KnownInformationAudience.PERSPECTIVE_PRIVATE,
                acquisitionReason = KnownInformationAcquisitionReason.PRIVATE_LIBRARY_LOOK,
            )
            return apply(initial, ExecutionResult.success(marked, listOf(LibraryShuffledEvent(p1))))
        }

        val first = evolve()
        val second = evolve()
        KnownInformationLedger.forPlayer(first, p1) shouldBe KnownInformationLedger.forPlayer(second, p1)
        stateJson().encodeToString(GameState.serializer(), first) shouldBe
            stateJson().encodeToString(GameState.serializer(), second)
    }

    test("HISTB-REVIEW-26 public library knowledge follows a named draw incarnation") {
        val cardId = EntityId.of("draw-known-top")
        val initial = stateWith(CardSpec(cardId, p2, Zone.LIBRARY, "Known Draw Card"))
        val known = KnownInformationLedger.recordCards(
            state = initial,
            cardIds = listOf(cardId),
            perspectivePlayerIds = listOf(p1, p2),
            audience = KnownInformationAudience.PUBLIC,
            acquisitionReason = KnownInformationAcquisitionReason.PUBLIC_REVEAL,
            includeLibraryPositions = true,
        )
        fact(known, p1, cardId, KnownInformationFactKind.POSITION_OR_ORDER).knownPosition shouldBe 0
        val moved = com.wingedsheep.engine.handlers.effects.ZoneTransitionService.moveToZone(
            state = known,
            entityId = cardId,
            destinationZone = Zone.HAND,
            fromZoneKey = ZoneKey(p2, Zone.LIBRARY),
        )
        val beforeStamp = checkNotNull(known.objectIdentityStamps[cardId])
        val afterStamp = checkNotNull(moved.state.objectIdentityStamps[cardId])
        afterStamp shouldNotBe beforeStamp

        val after = apply(
            known,
            ExecutionResult.success(
                moved.state,
                listOf(CardsDrawnEvent(p2, 1, listOf(cardId), listOf("Known Draw Card"))),
            ),
        )

        fact(after, p1, cardId, KnownInformationFactKind.IDENTITY).objectIdentityStamp shouldBe afterStamp
        fact(after, p1, cardId, KnownInformationFactKind.IDENTITY).knownZone shouldBe Zone.HAND
        fact(after, p1, cardId, KnownInformationFactKind.IDENTITY).audience shouldBe
            KnownInformationAudience.PUBLIC
        fact(after, p1, cardId, KnownInformationFactKind.ZONE_MEMBERSHIP).objectIdentityStamp shouldBe
            afterStamp
        fact(after, p1, cardId, KnownInformationFactKind.ZONE_MEMBERSHIP).knownZone shouldBe Zone.HAND
        facts(after, p1).none {
            it.subjectEntityId == cardId && it.factKind == KnownInformationFactKind.POSITION_OR_ORDER
        } shouldBe true
    }

    test("HISTB-REVIEW-27 hidden draw does not invent opponent continuity knowledge") {
        val cardId = EntityId.of("draw-hidden-card")
        val initial = stateWith(CardSpec(cardId, p2, Zone.LIBRARY, "Hidden Draw Card"))
        val moved = com.wingedsheep.engine.handlers.effects.ZoneTransitionService.moveToZone(
            state = initial,
            entityId = cardId,
            destinationZone = Zone.HAND,
            fromZoneKey = ZoneKey(p2, Zone.LIBRARY),
        )

        val after = apply(
            initial,
            ExecutionResult.success(
                moved.state,
                listOf(CardsDrawnEvent(p2, 1, listOf(cardId), listOf("Hidden Draw Card"))),
            ),
        )

        facts(after, p1).none { it.subjectEntityId == cardId } shouldBe true
    }

    test("HISTB-REVIEW-28 private library knowledge follows a named draw only to its viewer") {
        val cardId = EntityId.of("draw-private-known")
        val initial = stateWith(CardSpec(cardId, p2, Zone.LIBRARY, "Private Draw Card"))
        val known = LibraryRevealUtils.markRevealed(
            state = initial,
            cardIds = listOf(cardId),
            playerIds = listOf(p1),
            audience = KnownInformationAudience.PERSPECTIVE_PRIVATE,
            acquisitionReason = KnownInformationAcquisitionReason.PRIVATE_LIBRARY_LOOK,
        )
        val revealed = apply(initial, ExecutionResult.success(known))
        val moved = com.wingedsheep.engine.handlers.effects.ZoneTransitionService.moveToZone(
            state = revealed,
            entityId = cardId,
            destinationZone = Zone.HAND,
            fromZoneKey = ZoneKey(p2, Zone.LIBRARY),
        )
        val after = apply(
            revealed,
            ExecutionResult.success(
                moved.state,
                listOf(CardsDrawnEvent(p2, 1, listOf(cardId), listOf("Private Draw Card"))),
            ),
        )

        val afterStamp = checkNotNull(moved.state.objectIdentityStamps[cardId])
        fact(after, p1, cardId, KnownInformationFactKind.IDENTITY).objectIdentityStamp shouldBe afterStamp
        fact(after, p1, cardId, KnownInformationFactKind.IDENTITY).audience shouldBe
            KnownInformationAudience.PERSPECTIVE_PRIVATE
        fact(after, p1, cardId, KnownInformationFactKind.IDENTITY).knownZone shouldBe Zone.HAND
        facts(after, p2).none { it.subjectEntityId == cardId } shouldBe true
    }

    test("HISTB-REVIEW-29 identity and library membership without position do not follow a draw") {
        val cardId = EntityId.of("draw-known-without-position")
        val initial = stateWith(CardSpec(cardId, p2, Zone.LIBRARY, "Known Without Position"))
        val known = KnownInformationLedger.recordCards(
            state = initial,
            cardIds = listOf(cardId),
            perspectivePlayerIds = listOf(p1),
            audience = KnownInformationAudience.PUBLIC,
            acquisitionReason = KnownInformationAcquisitionReason.PUBLIC_REVEAL,
            includeLibraryPositions = false,
        )
        val moved = com.wingedsheep.engine.handlers.effects.ZoneTransitionService.moveToZone(
            state = known,
            entityId = cardId,
            destinationZone = Zone.HAND,
            fromZoneKey = ZoneKey(p2, Zone.LIBRARY),
        )

        val after = apply(
            known,
            ExecutionResult.success(
                moved.state,
                listOf(CardsDrawnEvent(p2, 1, listOf(cardId), listOf("Known Without Position"))),
            ),
        )

        facts(after, p1).none { it.subjectEntityId == cardId } shouldBe true
    }

    test("HISTB-REVIEW-30 position one does not authorize a single-card draw") {
        val first = EntityId.of("draw-position-zero")
        val cardId = EntityId.of("draw-position-one")
        val initial = stateWith(
            CardSpec(first, p2, Zone.LIBRARY, "First Card"),
            CardSpec(cardId, p2, Zone.LIBRARY, "Second Card"),
        )
        val known = KnownInformationLedger.recordCards(
            state = initial,
            cardIds = listOf(cardId),
            perspectivePlayerIds = listOf(p1),
            audience = KnownInformationAudience.PUBLIC,
            acquisitionReason = KnownInformationAcquisitionReason.PUBLIC_REVEAL,
            includeLibraryPositions = true,
        )
        fact(known, p1, cardId, KnownInformationFactKind.POSITION_OR_ORDER).knownPosition shouldBe 1
        val moved = com.wingedsheep.engine.handlers.effects.ZoneTransitionService.moveToZone(
            state = known,
            entityId = cardId,
            destinationZone = Zone.HAND,
            fromZoneKey = ZoneKey(p2, Zone.LIBRARY),
        )

        val after = apply(
            known,
            ExecutionResult.success(
                moved.state,
                listOf(CardsDrawnEvent(p2, 1, listOf(cardId), listOf("Second Card"))),
            ),
        )

        facts(after, p1).none { it.subjectEntityId == cardId } shouldBe true
    }

    test("HISTB-REVIEW-31 a known top-two draw follows both authorized positions") {
        val first = EntityId.of("draw-top-two-first")
        val second = EntityId.of("draw-top-two-second")
        val initial = stateWith(
            CardSpec(first, p2, Zone.LIBRARY, "Top Two First"),
            CardSpec(second, p2, Zone.LIBRARY, "Top Two Second"),
        )
        val known = KnownInformationLedger.recordCards(
            state = initial,
            cardIds = listOf(first, second),
            perspectivePlayerIds = listOf(p1),
            audience = KnownInformationAudience.PUBLIC,
            acquisitionReason = KnownInformationAcquisitionReason.PUBLIC_REVEAL,
            includeLibraryPositions = true,
        )
        val firstMoved = com.wingedsheep.engine.handlers.effects.ZoneTransitionService.moveToZone(
            state = known,
            entityId = first,
            destinationZone = Zone.HAND,
            fromZoneKey = ZoneKey(p2, Zone.LIBRARY),
        )
        val secondMoved = com.wingedsheep.engine.handlers.effects.ZoneTransitionService.moveToZone(
            state = firstMoved.state,
            entityId = second,
            destinationZone = Zone.HAND,
            fromZoneKey = ZoneKey(p2, Zone.LIBRARY),
        )

        val after = apply(
            known,
            ExecutionResult.success(
                secondMoved.state,
                listOf(
                    CardsDrawnEvent(
                        p2,
                        2,
                        listOf(first, second),
                        listOf("Top Two First", "Top Two Second"),
                    ),
                ),
            ),
        )

        listOf(first, second).forEach { cardId ->
            fact(after, p1, cardId, KnownInformationFactKind.IDENTITY).knownZone shouldBe Zone.HAND
            facts(after, p1).none {
                it.subjectEntityId == cardId && it.factKind == KnownInformationFactKind.POSITION_OR_ORDER
            } shouldBe true
        }
    }

    test("HISTB-REVIEW-32 shuffle-invalidated position does not authorize a draw") {
        val cardId = EntityId.of("draw-shuffled-known")
        val initial = stateWith(CardSpec(cardId, p2, Zone.LIBRARY, "Shuffled Known Card"))
        val known = KnownInformationLedger.recordCards(
            state = initial,
            cardIds = listOf(cardId),
            perspectivePlayerIds = listOf(p1),
            audience = KnownInformationAudience.PUBLIC,
            acquisitionReason = KnownInformationAcquisitionReason.PUBLIC_REVEAL,
            includeLibraryPositions = true,
        )
        val shuffled = apply(
            known,
            ExecutionResult.success(known, listOf(LibraryShuffledEvent(p2))),
        )
        facts(shuffled, p1).none {
            it.subjectEntityId == cardId && it.factKind == KnownInformationFactKind.POSITION_OR_ORDER
        } shouldBe true
        val moved = com.wingedsheep.engine.handlers.effects.ZoneTransitionService.moveToZone(
            state = shuffled,
            entityId = cardId,
            destinationZone = Zone.HAND,
            fromZoneKey = ZoneKey(p2, Zone.LIBRARY),
        )

        val after = apply(
            shuffled,
            ExecutionResult.success(
                moved.state,
                listOf(CardsDrawnEvent(p2, 1, listOf(cardId), listOf("Shuffled Known Card"))),
            ),
        )

        facts(after, p1).none { it.subjectEntityId == cardId } shouldBe true
    }

    test("HISTB-REVIEW-33 same-batch shuffle invalidates draw authority before the draw") {
        val cardId = EntityId.of("draw-same-batch-shuffle")
        val initial = stateWith(CardSpec(cardId, p2, Zone.LIBRARY, "Same Batch Shuffle Card"))
        val known = KnownInformationLedger.recordCards(
            state = initial,
            cardIds = listOf(cardId),
            perspectivePlayerIds = listOf(p1),
            audience = KnownInformationAudience.PUBLIC,
            acquisitionReason = KnownInformationAcquisitionReason.PUBLIC_REVEAL,
            includeLibraryPositions = true,
        )
        fact(known, p1, cardId, KnownInformationFactKind.POSITION_OR_ORDER).knownPosition shouldBe 0
        val moved = com.wingedsheep.engine.handlers.effects.ZoneTransitionService.moveToZone(
            state = known,
            entityId = cardId,
            destinationZone = Zone.HAND,
            fromZoneKey = ZoneKey(p2, Zone.LIBRARY),
        )

        val after = apply(
            known,
            ExecutionResult.success(
                moved.state,
                listOf(
                    LibraryShuffledEvent(p2),
                    CardsDrawnEvent(p2, 1, listOf(cardId), listOf("Same Batch Shuffle Card")),
                ),
            ),
        )

        facts(after, p1).none {
            it.subjectEntityId == cardId &&
                it.factKind == KnownInformationFactKind.IDENTITY &&
                it.knownZone == Zone.HAND
        } shouldBe true
    }

    test("HISTB-REVIEW-34 draw authority is evaluated before a later same-batch shuffle") {
        val cardId = EntityId.of("draw-before-same-batch-shuffle")
        val initial = stateWith(CardSpec(cardId, p2, Zone.LIBRARY, "Draw Before Shuffle Card"))
        val known = KnownInformationLedger.recordCards(
            state = initial,
            cardIds = listOf(cardId),
            perspectivePlayerIds = listOf(p1),
            audience = KnownInformationAudience.PUBLIC,
            acquisitionReason = KnownInformationAcquisitionReason.PUBLIC_REVEAL,
            includeLibraryPositions = true,
        )
        val moved = com.wingedsheep.engine.handlers.effects.ZoneTransitionService.moveToZone(
            state = known,
            entityId = cardId,
            destinationZone = Zone.HAND,
            fromZoneKey = ZoneKey(p2, Zone.LIBRARY),
        )

        val after = apply(
            known,
            ExecutionResult.success(
                moved.state,
                listOf(
                    CardsDrawnEvent(p2, 1, listOf(cardId), listOf("Draw Before Shuffle Card")),
                    LibraryShuffledEvent(p2),
                ),
            ),
        )

        fact(after, p1, cardId, KnownInformationFactKind.IDENTITY).knownZone shouldBe Zone.HAND
    }

    test("HISTB-REVIEW-35 same-batch library membership change invalidates draw authority") {
        val cardId = EntityId.of("draw-same-batch-membership")
        val enteringId = EntityId.of("draw-same-batch-entering")
        val initial = stateWith(
            CardSpec(cardId, p2, Zone.LIBRARY, "Same Batch Membership Card"),
            CardSpec(enteringId, p2, Zone.HAND, "Same Batch Entering Card"),
        )
        val known = KnownInformationLedger.recordCards(
            state = initial,
            cardIds = listOf(cardId),
            perspectivePlayerIds = listOf(p1),
            audience = KnownInformationAudience.PUBLIC,
            acquisitionReason = KnownInformationAcquisitionReason.PUBLIC_REVEAL,
            includeLibraryPositions = true,
        )
        fact(known, p1, cardId, KnownInformationFactKind.POSITION_OR_ORDER).knownPosition shouldBe 0
        val movedCard = com.wingedsheep.engine.handlers.effects.ZoneTransitionService.moveToZone(
            state = known,
            entityId = cardId,
            destinationZone = Zone.HAND,
            fromZoneKey = ZoneKey(p2, Zone.LIBRARY),
        )
        val movedEntering = com.wingedsheep.engine.handlers.effects.ZoneTransitionService.moveToZone(
            state = movedCard.state,
            entityId = enteringId,
            destinationZone = Zone.LIBRARY,
            fromZoneKey = ZoneKey(p2, Zone.HAND),
        )
        val membershipEvent = movedEntering.events.filterIsInstance<ZoneChangeEvent>().single()

        val after = apply(
            known,
            ExecutionResult.success(
                movedEntering.state,
                listOf(
                    membershipEvent,
                    CardsDrawnEvent(p2, 1, listOf(cardId), listOf("Same Batch Membership Card")),
                ),
            ),
        )

        facts(after, p1).none {
            it.subjectEntityId == cardId &&
                it.factKind == KnownInformationFactKind.IDENTITY &&
                it.knownZone == Zone.HAND
        } shouldBe true
    }
})
