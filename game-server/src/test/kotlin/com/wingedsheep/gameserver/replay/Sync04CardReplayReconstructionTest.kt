package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.ActionProcessor
import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.GameInitializer
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.SelectManaSourcesDecision
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.engine.legalactions.EnumerationMode
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.LegalActionEnumerator
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gameserver.ScenarioTestBase
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.StateDigest
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.time.Instant

/**
 * Card-specific A6 proofs for the two pinned Sync-04 changes that cross a real replay boundary.
 *
 * The live trajectory is produced by the same pure initializer/action processor that the replay
 * reconstructor folds. The assertion is deliberately made through CompactReplay -> ReplayCodec ->
 * ReplayReconstructor, not through a direct GameState JSON round trip.
 */
class Sync04CardReplayReconstructionTest : ScenarioTestBase() {

    private val processor by lazy { ActionProcessor(EngineServices(cardRegistry)) }
    private val initializer by lazy { GameInitializer(cardRegistry) }
    private val legalActionEnumerator by lazy { LegalActionEnumerator.create(cardRegistry) }
    private val observationBuilder by lazy { ObservationBuilder(cardRegistry = cardRegistry) }

    private data class Fixture(
        val setup: ReplaySetup,
        val player1: EntityId,
        val player2: EntityId,
        var state: GameState,
        val actions: MutableList<GameAction> = mutableListOf(),
    )

    private data class ObservationBoundary(
        val structural: TrainingObservation,
        val stateDigest: String,
        val semanticEngineActions: List<LegalAction>,
        val semanticObservationActions: List<com.wingedsheep.gym.contract.LegalActionView>,
    )

    private fun setup(
        seed: Long,
        player1Deck: Deck,
        player2Deck: Deck,
        startingHandSize: Int,
    ): ReplaySetup = ReplaySetup(
        seed = seed,
        format = Format.Standard,
        attackMode = AttackMode.MULTIPLE,
        startingHandSize = startingHandSize,
        skipMulligans = true,
        startingPlayerIndex = 0,
        players = listOf(
            ReplayPlayerSetup("sync04-card-p1", "Alice", player1Deck),
            ReplayPlayerSetup("sync04-card-p2", "Bob", player2Deck),
        ),
        // The A4 assertions below use the engine observation directly; the replay reconstruction
        // itself does not need a presentation seat roster to re-simulate the semantic state.
        seatRoster = emptyList(),
    )

    private fun initialize(replaySetup: ReplaySetup): GameState = initializer.initializeGame(
        GameConfig(
            players = replaySetup.players.map { player ->
                PlayerConfig(
                    name = player.name,
                    deck = player.deck,
                    startingLife = player.startingLife,
                    playerId = EntityId(player.playerId),
                    commanderCardName = player.commanderCardName,
                )
            },
            startingHandSize = replaySetup.startingHandSize,
            skipMulligans = replaySetup.skipMulligans,
            useHandSmoother = replaySetup.useHandSmoother,
            handSmootherCandidates = replaySetup.handSmootherCandidates,
            startingPlayerIndex = replaySetup.startingPlayerIndex,
            format = replaySetup.format,
            attackMode = replaySetup.attackMode,
            teams = replaySetup.teams,
            seed = replaySetup.seed,
        )
    ).state

    private fun fixture(
        label: String,
        player1Deck: Deck,
        player2Deck: Deck,
        startingHandSize: Int,
        initialStateMatches: (GameState, EntityId) -> Boolean,
    ): Fixture {
        val player1 = EntityId("sync04-card-p1")
        val player2 = EntityId("sync04-card-p2")
        for (seed in 1L..20_000L) {
            val replaySetup = setup(seed, player1Deck, player2Deck, startingHandSize)
            val state = initialize(replaySetup)
            if (initialStateMatches(state, player1)) {
                return Fixture(replaySetup, player1, player2, state)
            }
        }
        error("Could not find a deterministic $label setup in the bounded seed search")
    }

    private fun cardName(state: GameState, entityId: EntityId): String? =
        state.getEntity(entityId)?.get<CardComponent>()?.name

    private fun cardsIn(state: GameState, playerId: EntityId, zone: Zone, name: String): List<EntityId> =
        state.getZone(ZoneKey(playerId, zone)).filter { cardName(state, it) == name }

    private fun handHasAtLeast(state: GameState, playerId: EntityId, name: String, count: Int): Boolean =
        cardsIn(state, playerId, Zone.HAND, name).size >= count

    private fun submit(fixture: Fixture, action: GameAction): GameState {
        val result = processor.process(fixture.state, action).result
        check(result.error == null) {
            "${action::class.simpleName} was rejected in the card replay fixture: ${result.error}"
        }
        fixture.actions += action
        fixture.state = result.state
        return result.state
    }

    /** Advance through ordinary priority, answering only the mana-source prompts needed by setup. */
    private fun advanceUntil(
        fixture: Fixture,
        description: String,
        predicate: (GameState) -> Boolean,
    ) {
        repeat(600) {
            if (predicate(fixture.state)) return
            when (val pending = fixture.state.pendingDecision) {
                null -> {
                    val priority = fixture.state.priorityPlayerId
                        ?: error("$description lost priority without a pending decision")
                    submit(fixture, PassPriority(priority))
                }
                is SelectManaSourcesDecision -> submit(
                    fixture,
                    SubmitDecision(
                        pending.playerId,
                        ManaSourcesSelectedResponse(pending.id, emptyList(), autoPay = true),
                    ),
                )
                is com.wingedsheep.engine.core.SelectCardsDecision -> {
                    check(pending.prompt.startsWith("Discard down to")) {
                        "$description reached an unexpected card-selection prompt: ${pending.prompt}"
                    }
                    submit(
                        fixture,
                        SubmitDecision(
                            pending.playerId,
                            CardsSelectedResponse(pending.id, pending.options.take(pending.minSelections)),
                        ),
                    )
                }
                else -> error(
                    "$description reached an unexpected ${pending::class.simpleName} decision " +
                        "for ${pending.playerId} (${pending.prompt}) at " +
                        "${fixture.state.phase}/${fixture.state.step}, turn ${fixture.state.turnNumber}, " +
                        "active=${fixture.state.activePlayerId}, priority=${fixture.state.priorityPlayerId}",
                )
            }
        }
        error("Timed out while waiting for $description")
    }

    private fun advanceToPlayerMain(fixture: Fixture, playerId: EntityId, afterTurn: Int? = null) {
        advanceUntil(fixture, "${playerId.value} precombat main") { state ->
            state.activePlayerId == playerId &&
                state.priorityPlayerId == playerId &&
                state.phase == Phase.PRECOMBAT_MAIN &&
                state.step == Step.PRECOMBAT_MAIN &&
                (afterTurn == null || state.turnNumber > afterTurn)
        }
    }

    private fun playLandAndEndTurn(fixture: Fixture, playerId: EntityId, cardName: String) {
        advanceToPlayerMain(fixture, playerId)
        val land = cardsIn(fixture.state, playerId, Zone.HAND, cardName).firstOrNull()
            ?: error("No $cardName remained in the replay fixture hand")
        submit(fixture, PlayLand(playerId, land))
        val playedOnTurn = fixture.state.turnNumber
        advanceToPlayerMain(fixture, playerId, afterTurn = playedOnTurn)
    }

    private fun awaitPendingDecision(fixture: Fixture, description: String): YesNoDecision {
        advanceUntil(fixture, description) { it.pendingDecision is YesNoDecision }
        return fixture.state.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
    }

    private fun observationBoundary(state: GameState, perspective: EntityId): ObservationBoundary {
        val actor = state.pendingDecision?.playerId ?: state.priorityPlayerId
        val legalActions = if (state.pendingDecision == null && actor == perspective) {
            legalActionEnumerator.enumerate(state, perspective, EnumerationMode.ACTIONS_ONLY)
        } else {
            emptyList()
        }
        val observation = observationBuilder.build(
            state = state,
            perspectivePlayerId = perspective,
            legalActions = legalActions,
        ).observation as TrainingObservation
        val normalized = observation.copy(
            pendingDecision = observation.pendingDecision?.copy(decisionId = "D0"),
            stateDigest = "",
        )
        val withDigest = normalized.copy(stateDigest = StateDigest.compute(normalized))
        return ObservationBoundary(
            structural = withDigest.copy(legalActions = emptyList(), stateDigest = ""),
            stateDigest = withDigest.stateDigest,
            semanticEngineActions = legalActions
                .map { it.copy(description = "") }
                .sortedBy { it.toString() },
            semanticObservationActions = observation.legalActions
                .map { it.copy(actionId = 0, description = "") }
                .sortedBy { it.toString() },
        )
    }

    private fun assertObservationParity(live: GameState, reconstructed: GameState, perspective: EntityId) {
        val liveBoundary = observationBoundary(live, perspective)
        val reconstructedBoundary = observationBoundary(reconstructed, perspective)
        liveBoundary.structural shouldBe reconstructedBoundary.structural
        liveBoundary.stateDigest shouldBe reconstructedBoundary.stateDigest
        liveBoundary.semanticEngineActions shouldBe reconstructedBoundary.semanticEngineActions
        liveBoundary.semanticObservationActions shouldBe reconstructedBoundary.semanticObservationActions
    }

    private fun replayFor(fixture: Fixture, gameId: String): CompactReplay {
        val tail = ReplayFingerprint.of(fixture.state, CompactReplay.CURRENT_VERSION)
        return CompactReplay(
            gameId = gameId,
            players = fixture.setup.players.map { ReplayPlayerInfo(it.playerId, it.name) },
            startedAt = Instant.EPOCH.toString(),
            endedAt = Instant.EPOCH.toString(),
            winnerName = null,
            setup = fixture.setup,
            actions = fixture.actions.toList(),
            pinnedCards = ReplayCardPin.capture(cardRegistry, fixture.setup),
            checkpoints = ReplayCheckpointPolicy.withV3Tail(
                checkpoints = emptyList(),
                actionCount = fixture.actions.size,
                fingerprint = tail,
            ),
        )
    }

    private fun roundTripReplay(replay: CompactReplay): CompactReplay =
        ReplayCodec.decode(ReplayCodec.encode(replay)).also { it shouldBe replay }

    private fun hasCastAction(state: GameState, playerId: EntityId, cardId: EntityId): Boolean =
        legalActionEnumerator.enumerate(state, playerId, EnumerationMode.ACTIONS_ONLY).any { info ->
            (info.action as? CastSpell)?.cardId == cardId
        }

    init {
        test("Annoyed Altisaur Cascade decision and resolution reconstruct through CompactReplay") {
            val player1Deck = Deck(
                cards = buildList {
                    repeat(4) { add("Annoyed Altisaur") }
                    repeat(20) { add("Forest") }
                    repeat(8) { add("Grizzly Bears") }
                    repeat(8) { add("Mountain") }
                },
            )
            val player2Deck = Deck(cards = List(40) { "Forest" })
            val fixture = fixture(
                label = "Annoyed Altisaur",
                player1Deck = player1Deck,
                player2Deck = player2Deck,
                startingHandSize = 6,
            ) { state, playerId ->
                handHasAtLeast(state, playerId, "Annoyed Altisaur", 1) &&
                    handHasAtLeast(state, playerId, "Forest", 1) &&
                    state.getZone(ZoneKey(playerId, Zone.LIBRARY)).take(6)
                        .all { cardName(state, it) == "Forest" } &&
                    cardsIn(state, playerId, Zone.LIBRARY, "Grizzly Bears").isNotEmpty()
            }

            repeat(7) { playLandAndEndTurn(fixture, fixture.player1, "Forest") }
            advanceToPlayerMain(fixture, fixture.player1)
            val altisaur = cardsIn(fixture.state, fixture.player1, Zone.HAND, "Annoyed Altisaur").first()
            submit(
                fixture,
                CastSpell(
                    playerId = fixture.player1,
                    cardId = altisaur,
                    paymentStrategy = PaymentStrategy.AutoPay,
                ),
            )

            val cascadeDecision = awaitPendingDecision(fixture, "the Annoyed Altisaur Cascade decision")
            val pendingState = fixture.state
            val pendingActionCount = fixture.actions.size
            cascadeDecision.playerId shouldBe fixture.player1

            submit(fixture, SubmitDecision(fixture.player1, YesNoResponse(cascadeDecision.id, true)))
            advanceUntil(fixture, "Annoyed Altisaur and Cascade hit to resolve") { state ->
                state.pendingDecision == null &&
                    state.stack.isEmpty() &&
                    state.activePlayerId == fixture.player1 &&
                    state.priorityPlayerId == fixture.player1 &&
                    state.phase == Phase.PRECOMBAT_MAIN &&
                    state.step == Step.PRECOMBAT_MAIN
            }

            val replay = roundTripReplay(replayFor(fixture, "sync04-annoyed-replay"))
            val reconstructor = ReplayReconstructor(cardRegistry, null)
            val reconstructedPending = reconstructor.reconstructStateAt(replay, pendingActionCount)
                .shouldNotBeNull()
            val reconstructed = reconstructor.reconstruct(replay)
            val reconstructedFinal = reconstructor.reconstructStateAt(replay, replay.actions.size)
                .shouldNotBeNull()

            withClue("the Cascade decision boundary must preserve v3 semantic identity") {
                ReplayFingerprint.of(reconstructedPending, CompactReplay.CURRENT_VERSION) shouldBe
                    ReplayFingerprint.of(pendingState, CompactReplay.CURRENT_VERSION)
                reconstructedPending.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
                assertObservationParity(pendingState, reconstructedPending, fixture.player1)
            }
            withClue("the real Cascade cast-trigger trajectory must reconstruct exactly") {
                reconstructed.fidelity shouldBe ReplayFidelity.EXACT
                reconstructed.frameCount shouldBe 1 + replay.actions.size
                ReplayFingerprint.of(reconstructedFinal, CompactReplay.CURRENT_VERSION) shouldBe
                    ReplayFingerprint.of(fixture.state, CompactReplay.CURRENT_VERSION)
                cardsIn(reconstructedFinal, fixture.player1, Zone.BATTLEFIELD, "Annoyed Altisaur")
                    .shouldNotBeEmpty()
                cardsIn(reconstructedFinal, fixture.player1, Zone.BATTLEFIELD, "Grizzly Bears")
                    .shouldNotBeEmpty()
                assertObservationParity(fixture.state, reconstructedFinal, fixture.player1)
            }
        }

        test("Cori Mountain Monastery impulse permission reconstructs and expires deterministically") {
            val player1Deck = Deck(
                cards = buildList {
                    repeat(4) { add("Cori Mountain Monastery") }
                    repeat(20) { add("Mountain") }
                    repeat(8) { add("Grizzly Bears") }
                    repeat(8) { add("Forest") }
                },
            )
            val player2Deck = Deck(cards = List(40) { "Forest" })
            val fixture = fixture(
                label = "Cori Mountain Monastery",
                player1Deck = player1Deck,
                player2Deck = player2Deck,
                startingHandSize = 6,
            ) { state, playerId ->
                handHasAtLeast(state, playerId, "Cori Mountain Monastery", 1) &&
                    state.getZone(ZoneKey(playerId, Zone.LIBRARY)).take(6)
                        .all { cardName(state, it) == "Mountain" } &&
                    cardsIn(state, playerId, Zone.LIBRARY, "Grizzly Bears").isNotEmpty()
            }

            playLandAndEndTurn(fixture, fixture.player1, "Cori Mountain Monastery")
            repeat(6) { playLandAndEndTurn(fixture, fixture.player1, "Mountain") }
            advanceToPlayerMain(fixture, fixture.player1)

            val cori = fixture.state.getBattlefield(fixture.player1).first { cardName(fixture.state, it) == "Cori Mountain Monastery" }
            val impulseAbility = cardRegistry.getCard("Cori Mountain Monastery")!!
                .activatedAbilities.last()
            submit(fixture, ActivateAbility(fixture.player1, cori, impulseAbility.id))
            advanceUntil(fixture, "Cori Mountain Monastery impulse permission") { state ->
                state.pendingDecision == null &&
                    state.stack.isEmpty() &&
                    state.mayPlayPermissions.any { permission ->
                        permission.cardIds.any { cardId -> cardName(state, cardId) == "Grizzly Bears" }
                    } &&
                    state.activePlayerId == fixture.player1 &&
                    state.priorityPlayerId == fixture.player1
            }

            val exiled = cardsIn(fixture.state, fixture.player1, Zone.EXILE, "Grizzly Bears").first()
            fixture.state.mayPlayPermissions.any { it.cardIds.contains(exiled) } shouldBe true
            hasCastAction(fixture.state, fixture.player1, exiled) shouldBe true
            val permissionState = fixture.state
            val permissionActionCount = fixture.actions.size

            // UntilEndOfNextTurn: the permission survives the opponent's turn and the controller's
            // next turn, then expires during that next turn's cleanup.
            val currentTurn = fixture.state.turnNumber
            advanceUntil(fixture, "Cori current end step") {
                it.activePlayerId == fixture.player1 &&
                    it.turnNumber == currentTurn &&
                    it.phase == Phase.ENDING &&
                    it.step == Step.END
            }
            advanceUntil(fixture, "Cori opponent upkeep") {
                it.turnNumber > currentTurn && it.phase == Phase.BEGINNING && it.step == Step.UPKEEP
            }
            val opponentTurn = fixture.state.turnNumber
            advanceUntil(fixture, "Cori opponent end step") {
                it.turnNumber == opponentTurn && it.phase == Phase.ENDING && it.step == Step.END
            }
            advanceUntil(fixture, "Cori next upkeep") {
                it.turnNumber > opponentTurn && it.phase == Phase.BEGINNING && it.step == Step.UPKEEP
            }
            advanceToPlayerMain(fixture, fixture.player1, afterTurn = opponentTurn)

            val nextPlayerTurn = fixture.state.turnNumber
            fixture.state.mayPlayPermissions.any { it.cardIds.contains(exiled) } shouldBe true
            hasCastAction(fixture.state, fixture.player1, exiled) shouldBe true
            advanceUntil(fixture, "Cori next end step") {
                it.activePlayerId == fixture.player1 &&
                    it.turnNumber == nextPlayerTurn &&
                    it.phase == Phase.ENDING &&
                    it.step == Step.END
            }
            advanceToPlayerMain(fixture, fixture.player1, afterTurn = nextPlayerTurn)

            fixture.state.mayPlayPermissions.none { it.cardIds.contains(exiled) } shouldBe true
            hasCastAction(fixture.state, fixture.player1, exiled) shouldBe false
            val replay = roundTripReplay(replayFor(fixture, "sync04-cori-replay"))
            val reconstructor = ReplayReconstructor(cardRegistry, null)
            val reconstructedPermission = reconstructor.reconstructStateAt(replay, permissionActionCount)
                .shouldNotBeNull()
            val reconstructed = reconstructor.reconstruct(replay)
            val reconstructedFinal = reconstructor.reconstructStateAt(replay, replay.actions.size)
                .shouldNotBeNull()
            val reconstructedExiled = cardsIn(
                reconstructedPermission,
                fixture.player1,
                Zone.EXILE,
                "Grizzly Bears",
            ).first()

            withClue("the permission-bearing impulse state must reconstruct with legal play") {
                reconstructedPermission.mayPlayPermissions.any { it.cardIds.contains(reconstructedExiled) } shouldBe true
                hasCastAction(reconstructedPermission, fixture.player1, reconstructedExiled) shouldBe true
                ReplayFingerprint.of(reconstructedPermission, CompactReplay.CURRENT_VERSION) shouldBe
                    ReplayFingerprint.of(permissionState, CompactReplay.CURRENT_VERSION)
                assertObservationParity(permissionState, reconstructedPermission, fixture.player1)
            }
            withClue("the next-end-step expiry must reconstruct without a stale legal action") {
                reconstructed.fidelity shouldBe ReplayFidelity.EXACT
                reconstructed.frameCount shouldBe 1 + replay.actions.size
                reconstructedFinal.mayPlayPermissions.none { permission ->
                    permission.cardIds.any { cardId -> cardName(reconstructedFinal, cardId) == "Grizzly Bears" }
                } shouldBe true
                hasCastAction(reconstructedFinal, fixture.player1, reconstructedExiled) shouldBe false
                ReplayFingerprint.of(reconstructedFinal, CompactReplay.CURRENT_VERSION) shouldBe
                    ReplayFingerprint.of(fixture.state, CompactReplay.CURRENT_VERSION)
                assertObservationParity(fixture.state, reconstructedFinal, fixture.player1)
            }
        }
    }
}
