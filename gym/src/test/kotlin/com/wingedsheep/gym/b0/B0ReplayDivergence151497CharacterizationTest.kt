package com.wingedsheep.gym.b0

import com.wingedsheep.engine.core.ActionProcessor
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.GameInitializer
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.legalactions.EnumerationMode
import com.wingedsheep.engine.legalactions.LegalActionEnumerator
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gameserver.persistence.persistenceJson
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.gameserver.replay.CompactReplay
import com.wingedsheep.gameserver.replay.ReplayCheckpoint
import com.wingedsheep.gameserver.replay.ReplayCodec
import com.wingedsheep.gameserver.replay.ReplayFingerprint
import com.wingedsheep.gameserver.replay.ReplayFidelity
import com.wingedsheep.gameserver.replay.ReplayPlayerInfo
import com.wingedsheep.gameserver.replay.ReplayPlayerSetup
import com.wingedsheep.gameserver.replay.ReplayReconstructor
import com.wingedsheep.gameserver.replay.ReplaySetup
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.GameEnvironmentMode
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.service.DeckResolver
import com.wingedsheep.gym.service.EnvConfig
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.lang.reflect.Modifier
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes

/**
 * Exact seed-15 replay-divergence characterization.
 *
 * The B0 harness supplies the recorded live public trace and the accepted external action stream.
 * The test-only direct control below replays those exact actions through the strict Rules path and
 * records authoritative semantic checkpoints.  A second fold uses the existing CompactReplay
 * reconstruction path.  Neither path changes production behavior or policy decisions.
 */
class B0ReplayDivergence151497CharacterizationTest : FunSpec({

    test("localizes the exact seed-15 replay mismatch").config(timeout = 30.minutes) {
        val spec = B0EpisodeSpec.fourForBaseSeed(15L).single { episode ->
            episode.rosterOrientation == B0RosterOrientation.CHEVILL_SEAT_0 &&
                episode.startingPlayer == B0Commander.CHEVILL
        }
        spec.engineSeed shouldBe 15L
        spec.policySeed shouldBe 3902571074534369629L

        val runA = exactRun(spec)
        val runB = exactRun(spec)
        assertExactRun(runA)
        assertExactRun(runB)

        val originalA = LiveTrace(
            publicFrames = runA.semanticTrace.map(B0SemanticFrame::toPublicFrame),
            externalDecisionSequenceDigest = externalDecisionSequenceDigest(runA),
        )
        val originalB = LiveTrace(
            publicFrames = runB.semanticTrace.map(B0SemanticFrame::toPublicFrame),
            externalDecisionSequenceDigest = externalDecisionSequenceDigest(runB),
        )

        println(
            "B0_REPLAY_DIVERGENCE_15_1497_REPRODUCTION " +
                "runA=${runA.result.closureKind}/${runA.result.closureReason} " +
                "runB=${runB.result.closureKind}/${runB.result.closureReason} " +
                "acceptedA=${runA.acceptedActions.size} acceptedB=${runB.acceptedActions.size}",
        )
        println(
            "B0_REPLAY_DIVERGENCE_15_1497_EXTERNAL_SEQUENCE " +
                "count=${runA.acceptedActions.size} " +
                "digestA=${originalA.externalDecisionSequenceDigest} " +
                "digestB=${originalB.externalDecisionSequenceDigest}",
        )

        originalA.publicFrames shouldBe originalB.publicFrames
        originalA.externalDecisionSequenceDigest shouldBe originalB.externalDecisionSequenceDigest
        runA.acceptedActions.size shouldBe runB.acceptedActions.size

        val registry = fullCardRegistry()
        val resolver = DeckResolver(registry)
        val config = checkNotNull(runA.replayConfig)
        val directA = directStrictTrace(config, runA.acceptedActions, registry, resolver)
        val uncheckpointedA = replayWithoutCheckpoint(runA, config, resolver)
        val replayTraceA = replayTrace(uncheckpointedA, registry)
        val replayA = uncheckpointedA.copy(
            checkpoints = listOf(
                ReplayCheckpoint(
                    afterActionCount = uncheckpointedA.actions.size,
                    fingerprint = ReplayFingerprint.of(replayTraceA.finalState, uncheckpointedA.version),
                ),
            ),
        )
        val decodedA = ReplayCodec.decode(ReplayCodec.encode(replayA))
        val reconstructedA = ReplayReconstructor(registry, null).reconstruct(decodedA)
        reconstructedA.fidelity shouldBe ReplayFidelity.EXACT
        decodedA.actions shouldBe runA.acceptedActions
        ReplayFingerprint.of(replayTraceA.finalState, decodedA.version) shouldBe
            checkNotNull(runA.liveTailFingerprint)

        val liveReplayFirstDiff = firstDifferentPublicIndex(originalA.publicFrames, replayTraceA.publicFrames)
        val liveDirectFirstDiff = firstDifferentPublicIndex(originalA.publicFrames, directA.publicFrames)
        val directReplayPublicFirstDiff = firstDifferentPublicIndex(directA.publicFrames, replayTraceA.publicFrames)
        val authoritativeFirstDiff = firstDifferentCheckpoint(
            directA.semanticFingerprints,
            replayTraceA.semanticFingerprints,
        )
        val rngFirstDiff = firstDifferentCheckpoint(directA.rngStates, replayTraceA.rngStates)

        println(
            "B0_REPLAY_DIVERGENCE_15_1497_BOUNDARIES " +
                "liveVsReplay=${liveReplayFirstDiff ?: "none"} " +
                "liveVsDirect=${liveDirectFirstDiff ?: "none"} " +
                "directVsReplayPublic=${directReplayPublicFirstDiff ?: "none"} " +
                "directVsReplayAuthoritative=${authoritativeFirstDiff ?: "none"} " +
                "directVsReplayRng=${rngFirstDiff ?: "none"}",
        )

        // This is the known current verifier result, asserted as a transitionable diagnostic.
        liveReplayFirstDiff shouldBe 1_497
        liveDirectFirstDiff shouldBe null
        directReplayPublicFirstDiff shouldBe 1_497
        authoritativeFirstDiff shouldBe 576
        rngFirstDiff shouldBe null
        directA.semanticFingerprints.size shouldBe 1_498
        replayTraceA.semanticFingerprints.size shouldBe 1_498
        directA.rngStates.size shouldBe 1_498
        replayTraceA.rngStates.size shouldBe 1_498
        directA.semanticFingerprints[1_496] shouldBe replayTraceA.semanticFingerprints[1_496]
        publicScalarDiffs(originalA.publicFrames[1_496], replayTraceA.publicFrames[1_496]).shouldBeEmpty()
        val firstPublicDivergentFrame = checkNotNull(liveReplayFirstDiff)
        val firstDivergentFrame = checkNotNull(authoritativeFirstDiff)
        val lastEqualFrame = firstDivergentFrame - 1
        val firstDivergentDirectState = directA.capturedStates.getValue(firstDivergentFrame)
        val firstDivergentReplayState = replayTraceA.capturedStates.getValue(firstDivergentFrame)
        val authoritativeAtFirst = ReplayFingerprint.of(firstDivergentDirectState, replayA.version) ==
            ReplayFingerprint.of(firstDivergentReplayState, replayA.version)
        val rngEqualAtFirst = firstDivergentDirectState.rng == firstDivergentReplayState.rng
        val stateFieldDiffs = topLevelStateDiffs(firstDivergentDirectState, firstDivergentReplayState)
        val detailedDirectFirst = publicFrame(
            state = firstDivergentDirectState,
            fallback = checkNotNull(config.players[config.perspectivePlayerIndex].playerId),
            enumerator = directA.enumerator,
            observationBuilder = ObservationBuilder(cardRegistry = registry),
            terminalUsesFallback = true,
            includeActionSignatures = true,
        )
        val detailedReplayFirst = publicFrame(
            state = firstDivergentReplayState,
            fallback = checkNotNull(config.players[config.perspectivePlayerIndex].playerId),
            enumerator = replayTraceA.enumerator,
            observationBuilder = ObservationBuilder(cardRegistry = registry),
            includeActionSignatures = true,
        )
        val publicFieldDiffs = publicFrameDiffs(detailedDirectFirst, detailedReplayFirst)

        println(
                "B0_REPLAY_DIVERGENCE_15_1497_FIRST " +
                "lastEqualFrame=$lastEqualFrame firstDivergentFrame=$firstDivergentFrame " +
                "firstPublicDivergentFrame=$firstPublicDivergentFrame " +
                "precedingAction=${actionDescriptor(runA.acceptedActions[firstDivergentFrame - 1])} " +
                "precedingDecisionType=${runA.semanticTrace[firstDivergentFrame - 1].pendingDecisionFamily}",
        )
        println(
                "B0_REPLAY_DIVERGENCE_15_1497_DIGESTS " +
                "originalAtFirstAuthoritative=${originalA.publicFrames[firstDivergentFrame].publicStateDigest} " +
                "replayAtFirstAuthoritative=${replayTraceA.publicFrames[firstDivergentFrame].publicStateDigest} " +
                "originalAtFirstPublic=${originalA.publicFrames[firstPublicDivergentFrame].publicStateDigest} " +
                "replayAtFirstPublic=${replayTraceA.publicFrames[firstPublicDivergentFrame].publicStateDigest}",
        )
        println(
            "B0_REPLAY_DIVERGENCE_15_1497_STATE " +
                "semanticEqual=$authoritativeAtFirst " +
                "rawEqual=${firstDivergentDirectState == firstDivergentReplayState} " +
                "rngEqual=$rngEqualAtFirst " +
                "fieldDiffs=${stateFieldDiffs.ifEmpty { listOf("none") }}",
        )
        println(
            "B0_REPLAY_DIVERGENCE_15_1497_PUBLIC " +
                "fieldDiffs=${publicFieldDiffs.ifEmpty { listOf("none") }} " +
                "legalDomainEqual=${detailedDirectFirst.legalActionSignatures == detailedReplayFirst.legalActionSignatures}",
        )

        val frame1496 = 1_496
        val frame1497 = 1_497
        val state1496Direct = directA.capturedStates.getValue(frame1496)
        val state1497Direct = directA.capturedStates.getValue(frame1497)
        val state1496Replay = replayTraceA.capturedStates.getValue(frame1496)
        val state1497Replay = replayTraceA.capturedStates.getValue(frame1497)
        val detailedDirect1496 = publicFrame(
            state = state1496Direct,
            fallback = checkNotNull(config.players[config.perspectivePlayerIndex].playerId),
            enumerator = directA.enumerator,
            observationBuilder = ObservationBuilder(cardRegistry = registry),
            terminalUsesFallback = true,
            includeActionSignatures = true,
        )
        val detailedReplay1496 = publicFrame(
            state = state1496Replay,
            fallback = checkNotNull(config.players[config.perspectivePlayerIndex].playerId),
            enumerator = replayTraceA.enumerator,
            observationBuilder = ObservationBuilder(cardRegistry = registry),
            includeActionSignatures = true,
        )
        val detailedDirect1497 = publicFrame(
            state = state1497Direct,
            fallback = checkNotNull(config.players[config.perspectivePlayerIndex].playerId),
            enumerator = directA.enumerator,
            observationBuilder = ObservationBuilder(cardRegistry = registry),
            terminalUsesFallback = true,
            includeActionSignatures = true,
        )
        val detailedReplay1497 = publicFrame(
            state = state1497Replay,
            fallback = checkNotNull(config.players[config.perspectivePlayerIndex].playerId),
            enumerator = replayTraceA.enumerator,
            observationBuilder = ObservationBuilder(cardRegistry = registry),
            includeActionSignatures = true,
        )
        detailedDirect1496.legalActionSignatures shouldBe detailedReplay1496.legalActionSignatures
        detailedDirect1497.publicStateDigest shouldNotBe detailedReplay1497.publicStateDigest
        println(
            "B0_REPLAY_DIVERGENCE_15_1497_FRAME_1496_TO_1497 " +
                "action=${actionDescriptor(runA.acceptedActions[frame1496])} " +
                "semanticChoice=${runA.semanticTrace[frame1496].chosenSemanticIdentity} " +
                "decision=${runA.semanticTrace[frame1496].pendingDecisionFamily} " +
                "beforeDirect=${authoritySummary(state1496Direct)} " +
                "beforeReplay=${authoritySummary(state1496Replay)} " +
                "afterDirect=${authoritySummary(state1497Direct)} " +
                "afterReplay=${authoritySummary(state1497Replay)} " +
                "beforeLegalDomainEqual=${detailedDirect1496.legalActionSignatures == detailedReplay1496.legalActionSignatures} " +
                "afterStateEqual=${state1497Direct == state1497Replay} " +
                "terminalFallbackDigest=${detailedDirect1497.publicStateDigest} " +
                "terminalPriorityDigest=${detailedReplay1497.publicStateDigest} " +
                "terminalLivePerspective=${config.players[config.perspectivePlayerIndex].playerId?.value} " +
                "terminalReplayProjectionPerspective=${state1497Replay.priorityPlayerId?.value}",
        )
        println(
                "B0_REPLAY_DIVERGENCE_15_1497_FRAME_1496_TO_1497_PUBLIC " +
                "liveBefore=${publicFrameSummary(originalA.publicFrames[frame1496])} " +
                "directBefore=${publicFrameSummary(detailedDirect1496)} " +
                "replayBefore=${publicFrameSummary(detailedReplay1496)} " +
                "liveAfter=${publicFrameSummary(originalA.publicFrames[frame1497])} " +
                "directAfter=${publicFrameSummary(detailedDirect1497)} " +
                "replayAfter=${publicFrameSummary(detailedReplay1497)}",
        )

        val decisionReferencesDirect = normalizedDecisionReferenceComponents(firstDivergentDirectState)
        val decisionReferencesReplay = normalizedDecisionReferenceComponents(firstDivergentReplayState)
        val stateDifferenceOnlyDecisionReferences =
            stateFieldDiffs.isNotEmpty() &&
                decisionReferencesDirect == decisionReferencesReplay
        println(
            "B0_REPLAY_DIVERGENCE_15_1497_FIRST_STATE_COMPONENTS " +
                "referenceEqualAfterAliasNormalization=${decisionReferencesDirect == decisionReferencesReplay} " +
                "direct=$decisionReferencesDirect replay=$decisionReferencesReplay",
        )

        val separateTerminalProjectionFinding =
            firstPublicDivergentFrame == 1_497 &&
                detailedDirect1497.publicStateDigest != detailedReplay1497.publicStateDigest
        println(
            "B0_REPLAY_DIVERGENCE_15_1497_SEPARATE_TERMINAL_PROJECTION " +
                "firstPublicFrame=$firstPublicDivergentFrame " +
                "independentFinding=$separateTerminalProjectionFinding " +
                "stateEqual=${state1497Direct == state1497Replay} " +
                "fallbackDigest=${detailedDirect1497.publicStateDigest} " +
                "priorityDigest=${detailedReplay1497.publicStateDigest}",
        )
        separateTerminalProjectionFinding shouldBe true

        val checkpointFrame = replayA.checkpoints.single().afterActionCount
        println(
            "B0_REPLAY_DIVERGENCE_15_1497_CHECKPOINT " +
                "available=true frame=$checkpointFrame suffixReplay=not-applicable " +
                "reason=no-checkpoint-before-first-divergence",
        )

        val classification = classify(
            live = originalA.publicFrames,
            direct = directA,
            replay = replayTraceA,
            firstFrame = firstDivergentFrame,
            firstState = firstDivergentDirectState,
            fallbackPerspective = checkNotNull(config.players[config.perspectivePlayerIndex].playerId),
            authoritativeAtFirst = authoritativeAtFirst,
            rngEqualAtFirst = rngEqualAtFirst,
            stateDifferenceOnlyDecisionReferences = stateDifferenceOnlyDecisionReferences,
            stateFieldDiffs = stateFieldDiffs,
            publicFieldDiffs = publicFieldDiffs,
        )
        println(
            "B0_REPLAY_DIVERGENCE_15_1497_CLASSIFICATION " +
                "primary=${classification.primary} secondary=${classification.secondary} " +
                "root=${classification.root} " +
                "unstableMapSet=${classification.unstableMapSet} " +
                "unstableEntityId=${classification.unstableEntityId} " +
                "unstableActionDecisionId=${classification.unstableActionDecisionId} " +
                "unstableToStringHash=${classification.unstableToStringHash} " +
                "rngCursorDrift=${classification.rngCursorDrift}",
        )
        classification.primary shouldBe "E=UNSTABLE_ID_ORDERING_OR_FINGERPRINT_DIVERGENCE"
        classification.root shouldBe
            "semantic-fingerprint-divergence-despite-preserved-pending-continuation-reference-aliases"
        classification.unstableActionDecisionId shouldBe "FOUND_IN_TYPED_DECISION_REFERENCE_SLOTS"
        classification.secondary shouldBe "none"

        println(
            "B0_REPLAY_DIVERGENCE_15_1497_DETERMINISM " +
                "originalSelf=${originalA.publicFrames == originalB.publicFrames} " +
                "replaySelf=NOT_RUN " +
                "fullVsReplay=false " +
                "checkpointSuffix=not-run",
        )

        // Keep the characterization diagnostic, not a production fix: this failure is expected
        // on the exact base until an independently authorized fix is implemented.
        reconstructedA.divergenceReason shouldBe null
    }
})

private data class LiveTrace(
    val publicFrames: List<PublicFrame>,
    val externalDecisionSequenceDigest: String,
)

private data class ReplayTrace(
    val semanticFingerprints: Map<Int, String>,
    val rngStates: Map<Int, Long>,
    val publicFrames: List<PublicFrame>,
    val capturedStates: Map<Int, GameState>,
    val finalState: GameState,
    val enumerator: LegalActionEnumerator,
)

private data class PublicFrame(
    val publicStateDigest: String,
    val legalActionFamiliesInPublishedOrder: List<String>,
    val legalActionSignatures: List<String>,
    val pendingDecisionFamily: String?,
)

private data class Classification(
    val primary: String,
    val secondary: String,
    val root: String,
    val unstableMapSet: String,
    val unstableEntityId: String,
    val unstableActionDecisionId: String,
    val unstableToStringHash: String,
    val rngCursorDrift: String,
)

private fun exactRun(spec: B0EpisodeSpec): B0EpisodeRun =
    B0CommanderSoakHarness.create().run(
        spec = spec,
        control = B0RunControl(
            semanticActionBudget = 2_000,
            engineProgressBudget = 10_000,
        ),
    )

private fun assertExactRun(run: B0EpisodeRun) {
    run.failureBundle.shouldBeNull()
    run.result.semanticExternalDecisionCount shouldBe 1_497
    run.result.externalTransitionCount shouldBe 1_497
    run.acceptedActions.size shouldBe 1_497
    run.semanticTrace.size shouldBe 1_498
}

private fun B0SemanticFrame.toPublicFrame(): PublicFrame = PublicFrame(
    publicStateDigest = publicStateDigest,
    legalActionFamiliesInPublishedOrder = legalActionFamiliesInPublishedOrder,
    // The live harness intentionally stores only the public family projection. Detailed legal
    // action signatures are generated from the direct/reconstructed public observations below.
    legalActionSignatures = emptyList(),
    pendingDecisionFamily = pendingDecisionFamily,
)

private fun fullCardRegistry(): CardRegistry = CardRegistry().apply {
    MtgSetCatalog.all.forEach { set ->
        register(set.cards)
        register(set.basicLands)
    }
}

private fun EnvConfig.toGameConfig(resolver: DeckResolver): GameConfig = GameConfig(
    players = players.map { player ->
        PlayerConfig(
            name = player.name,
            deck = resolver.resolve(player.deck),
            startingLife = player.startingLife,
            playerId = player.playerId,
            commanderCardName = player.commanderCardName,
        )
    },
    startingHandSize = startingHandSize,
    skipMulligans = skipMulligans,
    useHandSmoother = useHandSmoother,
    startingPlayerIndex = startingPlayerIndex,
    format = format,
    attackMode = AttackMode.MULTIPLE,
    seed = seed,
)

private fun replayWithoutCheckpoint(
    run: B0EpisodeRun,
    config: EnvConfig,
    resolver: DeckResolver,
): CompactReplay {
    val setup = config.toReplaySetup(resolver)
    val uncheckpointed = CompactReplay(
        gameId = run.result.episodeId,
        players = config.players.map { player ->
            ReplayPlayerInfo(
                playerId = checkNotNull(player.playerId).value,
                name = player.name,
            )
        },
        startedAt = "1970-01-01T00:00:00Z",
        endedAt = "1970-01-01T00:00:00Z",
        winnerName = run.result.winner,
        setup = setup,
        actions = run.acceptedActions,
        engineVersion = run.result.engineCommit,
    )
    return uncheckpointed
}

private fun EnvConfig.toReplaySetup(resolver: DeckResolver): ReplaySetup = ReplaySetup(
    seed = checkNotNull(seed),
    format = format,
    attackMode = AttackMode.MULTIPLE,
    startingHandSize = startingHandSize,
    skipMulligans = skipMulligans,
    useHandSmoother = useHandSmoother,
    startingPlayerIndex = startingPlayerIndex,
    players = players.map { player ->
        ReplayPlayerSetup(
            playerId = checkNotNull(player.playerId).value,
            name = player.name,
            deck = resolver.resolve(player.deck),
            startingLife = player.startingLife,
            commanderCardName = player.commanderCardName,
        )
    },
    seatRoster = players.mapIndexed { index, player ->
        ServerMessage.PlayerSeatInfo(
            playerId = checkNotNull(player.playerId).value,
            name = player.name,
            seatIndex = index,
        )
    },
)

private fun replayTrace(replay: CompactReplay, registry: CardRegistry): ReplayTrace {
    require(replay.yields.isEmpty()) { "B0 exact replay unexpectedly carries out-of-band yields" }
    var state = initialState(replay.setup, registry)
    val processor = ActionProcessor(EngineServices(registry), computeUndo = false)
    val legalActions = LegalActionEnumerator.create(registry)
    val observationBuilder = ObservationBuilder(cardRegistry = registry)
    val fingerprints = linkedMapOf<Int, String>()
    val rngStates = linkedMapOf<Int, Long>()
    val publicFrames = mutableListOf<PublicFrame>()
    val capturedStates = linkedMapOf<Int, GameState>()
    val captureFrames = setOf(0, 575, 576, 1_496, 1_497)

    fun capture(frame: Int) {
        fingerprints[frame] = ReplayFingerprint.of(state, replay.version)
        rngStates[frame] = state.rng.state
        publicFrames += publicFrame(
            state = state,
            fallback = EntityId(replay.setup.players.first().playerId),
            enumerator = legalActions,
            observationBuilder = observationBuilder,
        )
        if (frame in captureFrames) capturedStates[frame] = state
    }

    capture(0)
    replay.actions.forEachIndexed { index, recorded ->
        val result = processor.process(state, rebind(recorded, state)).result
        require(result.error == null) { "test replay fold rejected action: ${result.error}" }
        require(result.diagnostics.isEmpty()) {
            "test replay fold produced diagnostics: ${result.diagnostics}"
        }
        state = result.state
        capture(index + 1)
    }
    return ReplayTrace(fingerprints, rngStates, publicFrames, capturedStates, state, legalActions)
}

private fun directStrictTrace(
    config: EnvConfig,
    actions: List<GameAction>,
    registry: CardRegistry,
    resolver: DeckResolver,
): ReplayTrace {
    val environment = GameEnvironment.create(registry, executionMode = GameEnvironmentMode.TRUSTED)
    environment.reset(config.toGameConfig(resolver), maxSteps = config.maxSteps)
    val legalActions = LegalActionEnumerator.create(registry)
    val observationBuilder = ObservationBuilder(cardRegistry = registry)
    val fingerprints = linkedMapOf<Int, String>()
    val rngStates = linkedMapOf<Int, Long>()
    val publicFrames = mutableListOf<PublicFrame>()
    val capturedStates = linkedMapOf<Int, GameState>()
    val captureFrames = setOf(0, 575, 576, 1_496, 1_497)

    fun capture(frame: Int) {
        fingerprints[frame] = ReplayFingerprint.of(environment.state)
        rngStates[frame] = environment.state.rng.state
        val fallback = environment.playerIds.first()
        // This mirrors GameGymEnv.currentPerspective(): terminal states use the configured
        // fallback player, while active states use the pending decision/priority player.
        publicFrames += publicFrame(
            state = environment.state,
            fallback = fallback,
            enumerator = legalActions,
            observationBuilder = observationBuilder,
            terminalUsesFallback = true,
        )
        if (frame in captureFrames) capturedStates[frame] = environment.state
    }

    capture(0)
    actions.forEachIndexed { index, recorded ->
        environment.stepStrict(rebind(recorded, environment.state))
        capture(index + 1)
    }
    return ReplayTrace(fingerprints, rngStates, publicFrames, capturedStates, environment.state, legalActions)
}

private fun initialState(setup: ReplaySetup, registry: CardRegistry): GameState {
    val config = GameConfig(
        players = setup.players.map { player ->
            PlayerConfig(
                name = player.name,
                deck = player.deck,
                startingLife = player.startingLife,
                playerId = EntityId(player.playerId),
                commanderCardName = player.commanderCardName,
            )
        },
        startingHandSize = setup.startingHandSize,
        skipMulligans = setup.skipMulligans,
        useHandSmoother = setup.useHandSmoother,
        handSmootherCandidates = setup.handSmootherCandidates,
        startingPlayerIndex = setup.startingPlayerIndex,
        format = setup.format,
        attackMode = setup.attackMode,
        teams = setup.teams,
        seed = setup.seed,
    )
    return GameInitializer(registry).initializeGame(config).state
}

private fun rebind(action: GameAction, state: GameState): GameAction =
    if (action is SubmitDecision) {
        state.pendingDecision?.id?.let { action.copy(response = action.response.withDecisionId(it)) } ?: action
    } else {
        action
    }

private fun publicFrame(
    state: GameState,
    fallback: EntityId,
    enumerator: LegalActionEnumerator,
    observationBuilder: ObservationBuilder,
    terminalUsesFallback: Boolean = false,
    includeActionSignatures: Boolean = false,
): PublicFrame {
    val perspective = if (terminalUsesFallback && state.gameOver) {
        fallback
    } else {
        state.pendingDecision?.playerId ?: state.priorityPlayerId ?: fallback
    }
    val actions = if (!state.gameOver && state.pendingDecision == null) {
        enumerator.enumerate(state, perspective, EnumerationMode.ACTIONS_ONLY)
            .filterNot { it.hasUnfillableTargetRequirement }
    } else {
        emptyList()
    }
    val result = observationBuilder.build(state, perspective, actions)
    require(result.diagnostics.isEmpty()) { "test public projection produced diagnostics: ${result.diagnostics}" }
    val observation = result.observation as TrainingObservation
    return PublicFrame(
        publicStateDigest = observation.stateDigest,
        legalActionFamiliesInPublishedOrder = observation.legalActions.map { it.kind },
        legalActionSignatures = if (includeActionSignatures) {
            observation.legalActions.map { legalActionSignature(it) }
        } else {
            emptyList()
        },
        pendingDecisionFamily = observation.pendingDecision?.kind?.name,
    )
}

private fun legalActionSignature(action: LegalActionView): String {
    val normalized = action.copy(actionId = 0, description = "")
    return canonicalJson(persistenceJson.encodeToJsonElement(LegalActionView.serializer(), normalized))
}

private fun externalDecisionSequenceDigest(run: B0EpisodeRun): String = sha256(
    run.semanticTrace
        .asSequence()
        .mapNotNull(B0SemanticFrame::chosenSemanticIdentity)
        .joinToString("\n"),
)

private fun <T> firstDifferentCheckpoint(left: Map<Int, T>, right: Map<Int, T>): Int? {
    val frames = (left.keys + right.keys).distinct().sorted()
    return frames.firstOrNull { frame -> left[frame] != right[frame] }
}

private fun firstDifferentPublicIndex(left: List<PublicFrame>, right: List<PublicFrame>): Int? {
    val common = minOf(left.size, right.size)
    val mismatch = (0 until common).firstOrNull { index ->
        !sameSemanticProjection(left[index], right[index])
    }
    return mismatch ?: common.takeIf { left.size != right.size }
}

private fun sameSemanticProjection(left: PublicFrame, right: PublicFrame): Boolean =
    left.publicStateDigest == right.publicStateDigest &&
        left.legalActionFamiliesInPublishedOrder == right.legalActionFamiliesInPublishedOrder &&
        left.pendingDecisionFamily == right.pendingDecisionFamily

private fun publicScalarDiffs(left: PublicFrame, right: PublicFrame): List<String> = buildList {
    if (left.publicStateDigest != right.publicStateDigest) add("publicStateDigest")
    if (left.legalActionFamiliesInPublishedOrder != right.legalActionFamiliesInPublishedOrder) {
        add("legalActionFamiliesInPublishedOrder")
    }
    if (left.pendingDecisionFamily != right.pendingDecisionFamily) add("pendingDecisionFamily")
}

private fun publicFrameDiffs(left: PublicFrame, right: PublicFrame): List<String> = buildList {
    if (left.publicStateDigest != right.publicStateDigest) add("publicStateDigest")
    if (left.legalActionFamiliesInPublishedOrder != right.legalActionFamiliesInPublishedOrder) {
        add("legalActionFamiliesInPublishedOrder")
    }
    if (left.legalActionSignatures != right.legalActionSignatures) add("legalActionSignatures")
    if (left.pendingDecisionFamily != right.pendingDecisionFamily) add("pendingDecisionFamily")
}

private fun normalizedDecisionReferenceComponents(state: GameState): String {
    val stateJson = persistenceJson.encodeToJsonElement(GameState.serializer(), state).jsonObject
    val aliases = linkedMapOf<String, String>()

    fun alias(raw: String): JsonPrimitive =
        JsonPrimitive(aliases.getOrPut(raw) { "D${aliases.size}" })

    val pending = (stateJson["pendingDecision"] as? JsonObject)?.let { decision ->
        val id = decision["id"] as? JsonPrimitive
        if (id == null || !id.isString) {
            decision
        } else {
            JsonObject(decision + ("id" to alias(id.content)))
        }
    } ?: stateJson["pendingDecision"] ?: JsonNull

    val continuation = (stateJson["continuationStack"] as? JsonArray)?.let { frames ->
        JsonArray(frames.map { frame ->
            val objectFrame = frame as? JsonObject ?: return@map frame
            val id = objectFrame["decisionId"] as? JsonPrimitive
            if (id == null || !id.isString) {
                objectFrame
            } else {
                val continuationAlias = alias(id.content)
                val decisionShape = objectFrame["decisionShape"] as? JsonObject
                val shaped = if (decisionShape != null) {
                    val shapeId = decisionShape["id"] as? JsonPrimitive
                    if (shapeId != null && shapeId.isString && shapeId.content == id.content) {
                        JsonObject(decisionShape + ("id" to continuationAlias))
                    } else {
                        decisionShape
                    }
                } else {
                    null
                }
                JsonObject(
                    objectFrame + buildMap {
                        put("decisionId", continuationAlias)
                        if (shaped != null) put("decisionShape", shaped)
                    },
                )
            }
        })
    } ?: stateJson["continuationStack"] ?: JsonArray(emptyList())

    return canonicalJson(
        JsonObject(
            linkedMapOf(
                "pendingDecision" to pending,
                "continuationStack" to continuation,
            ),
        ),
    )
}

private fun classify(
    live: List<PublicFrame>,
    direct: ReplayTrace,
    replay: ReplayTrace,
    firstFrame: Int,
    firstState: GameState,
    fallbackPerspective: EntityId,
    authoritativeAtFirst: Boolean,
    rngEqualAtFirst: Boolean,
    stateDifferenceOnlyDecisionReferences: Boolean,
    stateFieldDiffs: List<String>,
    publicFieldDiffs: List<String>,
): Classification {
    val stateSemanticEqual = authoritativeAtFirst
    val rngEqual = rngEqualAtFirst
    val rawFieldDifference = stateFieldDiffs.isNotEmpty()
    val liveFieldDiffs = publicScalarDiffs(live[firstFrame], replay.publicFrames[firstFrame])
    val publicDifference = publicFieldDiffs.isNotEmpty() || liveFieldDiffs.isNotEmpty()
    val primary = when {
        !stateSemanticEqual && stateDifferenceOnlyDecisionReferences ->
            "E=UNSTABLE_ID_ORDERING_OR_FINGERPRINT_DIVERGENCE"
        !stateSemanticEqual -> "A=AUTHORITATIVE_STATE_DIVERGENCE"
        rawFieldDifference && publicDifference -> "E=UNSTABLE_ID_ORDERING_OR_FINGERPRINT_DIVERGENCE"
        publicDifference && direct.publicFrames[firstFrame].legalActionSignatures !=
            replay.publicFrames[firstFrame].legalActionSignatures ->
            "C=LEGAL_ACTION_DECISION_DOMAIN_DIVERGENCE"
        publicDifference -> "B=PUBLIC_PROJECTION_ONLY_DIVERGENCE"
        else -> "E=UNSTABLE_ID_ORDERING_OR_FINGERPRINT_DIVERGENCE"
    }
    val secondary = buildList {
        liveFieldDiffs.forEach { add("live-$it") }
        if (!rngEqual) add("rng-state-difference")
        if (publicFieldDiffs.contains("legalActionSignatures")) add("public-legal-domain-difference")
    }.joinToString(",").ifBlank { "none" }
    return Classification(
        primary = primary,
        secondary = secondary,
        root = if (!stateSemanticEqual && stateDifferenceOnlyDecisionReferences) {
            "semantic-fingerprint-divergence-despite-preserved-pending-continuation-reference-aliases"
        } else if (!stateSemanticEqual) {
            "authoritative-state-transition; differing-fields=" +
                stateFieldDiffs.joinToString(",").ifBlank { "not-isolated" }
        } else if (firstState.gameOver && firstState.priorityPlayerId != fallbackPerspective) {
            "replay-semantic-projection-uses-terminal-priority-instead-of-gym-fallback-perspective"
        } else {
            "UNRESOLVED"
        },
        unstableMapSet = if (stateFieldDiffs.any { it.contains("zone", ignoreCase = true) }) "FOUND_OR_REQUIRES_AUDIT" else "NOT_FOUND",
        unstableEntityId = if (stateFieldDiffs.any { it.contains("Entity", ignoreCase = true) }) "FOUND_OR_REQUIRES_AUDIT" else "NOT_FOUND",
        unstableActionDecisionId = if (stateDifferenceOnlyDecisionReferences) {
            "FOUND_IN_TYPED_DECISION_REFERENCE_SLOTS"
        } else if (publicFieldDiffs.any { it.contains("legalAction") }) {
            "NOT_FOUND_IN_SEMANTIC_SIGNATURE"
        } else {
            "NOT_FOUND"
        },
        unstableToStringHash = "NOT_FOUND",
        rngCursorDrift = if (rngEqual) "NOT_FOUND" else "FOUND",
    )
}

private fun topLevelStateDiffs(left: GameState, right: GameState): List<String> = buildList {
    GameState::class.java.declaredFields
        // Kotlin's lazy projected-state caches are implementation details and are not data-class
        // state. They have distinct delegate instances even when every authoritative field agrees.
        .filterNot { field ->
            Modifier.isStatic(field.modifiers) || field.isSynthetic || field.name.endsWith("\$delegate")
        }
        .forEach { field ->
            field.isAccessible = true
            if (field.get(left) != field.get(right)) add(field.name)
        }
}

private fun authoritySummary(state: GameState): String =
    "turn=${state.turnNumber},phase=${state.phase},step=${state.step}," +
        "active=${state.activePlayerId?.value},priority=${state.priorityPlayerId?.value}," +
        "gameOver=${state.gameOver},winner=${state.winnerId?.value}," +
        "rng=${state.rng.state},nextEntity=${state.nextEntityId}," +
        "stack=${state.stack.joinToString(",") { it.value }}," +
        "pending=${state.pendingDecision?.let { it::class.simpleName }}," +
        "continuations=${state.continuationStack.size},floating=${state.floatingEffects.size}," +
        "delayed=${state.delayedTriggers.size},grantedTriggers=${state.grantedTriggeredAbilities.size}," +
        "zones=${state.zones.entries.sortedBy { it.key.toString() }.joinToString(",") { entry ->
            "${entry.key.ownerId.value}:${entry.key.zoneType}=${entry.value.size}"
        }}"

private fun publicFrameSummary(frame: PublicFrame): String =
    "digest=${frame.publicStateDigest},families=${frame.legalActionFamiliesInPublishedOrder}," +
        "domainCount=${frame.legalActionSignatures.size},pending=${frame.pendingDecisionFamily}"

private fun actionDescriptor(action: GameAction): String = when (action) {
    is SubmitDecision -> "${action::class.simpleName}(player=${action.playerId.value},response=${action.response::class.simpleName})"
    else -> "${action::class.simpleName}(player=${action.playerId.value})"
}

private fun canonicalJson(element: JsonElement): String = when (element) {
    JsonNull -> "null"
    is JsonPrimitive -> element.toString()
    is JsonArray -> element.joinToString(prefix = "[", postfix = "]", separator = ",", transform = ::canonicalJson)
    is JsonObject -> element.keys.sorted().joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
        "$key:${canonicalJson(element.getValue(key))}"
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
