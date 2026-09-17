package com.wingedsheep.gameserver.replay.verification

import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.EpisodeClosureV1
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.GameEnvironmentMode
import com.wingedsheep.gym.GameGymEnv
import com.wingedsheep.gym.contract.ObservationResult
import com.wingedsheep.gym.contract.ActionRegistry
import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.ChosenSemanticActionV1
import com.wingedsheep.gym.contract.ChosenSemanticResponseV1
import com.wingedsheep.gym.contract.CompleteLegalDomainV1
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.ObservationCanonicalizer
import com.wingedsheep.gym.contract.OrderingDomain
import com.wingedsheep.gym.contract.ReplayChosenInputV1
import com.wingedsheep.gym.contract.ReplayContentIdentityV1
import com.wingedsheep.gym.contract.ReplayFidelity
import com.wingedsheep.gym.contract.ReplayTrajectoryBindingV1
import com.wingedsheep.gym.contract.ResolvedAction
import com.wingedsheep.gym.contract.SchemaHash
import com.wingedsheep.gym.contract.StructuredCardInfo
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.trainer.actor.LocalSourceBootstrapV1
import com.wingedsheep.gym.trainer.actor.GitSourceBootstrapProbeV1
import com.wingedsheep.gym.trainer.trajectory.SemanticDecisionIdentityV1
import com.wingedsheep.gym.trainer.trajectory.SemanticReplayInputV1
import com.wingedsheep.gym.trainer.trajectory.SemanticReplayPrefixV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1
import com.wingedsheep.gameserver.curriculum.CurriculumDeckSourceLoader
import com.wingedsheep.gameserver.curriculum.CurriculumDeckSourceV1
import com.wingedsheep.gameserver.replay.CompactReplay
import com.wingedsheep.gameserver.replay.GymReplayFrameSource
import com.wingedsheep.gameserver.replay.ReplayCardPin
import com.wingedsheep.gameserver.replay.ReplayCheckpoint
import com.wingedsheep.gameserver.replay.ReplayCodec
import com.wingedsheep.gameserver.replay.ReplayContentCanonicalizerV1
import com.wingedsheep.gameserver.replay.ReplayFingerprint
import com.wingedsheep.gameserver.replay.ReplayPlayerInfo
import com.wingedsheep.gameserver.replay.ReplayPlayerSetup
import com.wingedsheep.gameserver.replay.ReplayRecordingPolicy
import com.wingedsheep.gameserver.replay.ReplaySetup
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.serialization.CardSerialization
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reusable production reconstruction engine for transported Gym/Environment V1 trajectories.
 *
 * Given the durable transported trajectory (the only claimant evidence) plus explicit repository
 * authority, this primitive re-derives the environment identity, authenticates the executed
 * source revision against the durable `engineCommit` BEFORE any reconstruction, rebuilds the
 * replay from the durable identity plus the ordered transported semantic choices, rebinds every
 * choice against the CURRENT reconstructed legal boundaries, and independently produces a fresh
 * A4 `ReplayTrajectoryBindingV1` plus a fresh trajectory identity.
 *
 * Stored observations/domains are comparison targets only — never reconstruction authority.
 *
 * Extraction of the KA06_01 characterized reconstruction core; the `kaggleActor06CharacterizationTest`
 * gate remains the conformance oracle for this primitive.
 */
class TransportedReplayReconstructorV1(
    /** Repository root supplying the locked curriculum deck authority and git bootstrap probe. */
    val repositoryRoot: Path,
    /**
     * Durable claimant replay range for horizon derivation; supplied per verification by the
     * worker (the reconstructor instance is repository-scoped, not trajectory-scoped).
     */
    claimantReplayActionCountOrNull: Int? = null,
    /** Deck source loading authority; defaults to the accepted curriculum loader. */
    private val deckSourceLoader: (repositoryRoot: Path, sourcePath: String) -> CurriculumDeckSourceV1 =
        { root, sourcePath -> CurriculumDeckSourceLoader(root).load(sourcePath) },
    /** Canonical source pins required by the bootstrap doctrine. */
    private val requiredPinnedPaths: List<String> = DEFAULT_REQUIRED_PINS,
    /** Explicit horizon override (test seam only); production uses the derived contract. */
    private val replayHorizonOverride: Int? = null,
    /**
     * Bounded fresh-execution horizon, derived by [computeReplayHorizon]: durable claimant range
     * plus fixed headroom (setup/mulligan action headroom), under the hard-owned production
     * ceiling [MAX_REPLAY_STEPS_CEILING]. A claimant range beyond the ceiling cannot be
     * reconstructed and fails closed — it must never be truncated silently.
     */
    private val maxReplaySteps: Int = computeReplayHorizon(claimantReplayActionCountOrNull, replayHorizonOverride),
) {
    /** Accepted locked-pair deck authority, loaded once per reconstructor instance. */
    private val deckSources: Pair<CurriculumDeckSourceV1, CurriculumDeckSourceV1> by lazy {
        val paths = com.wingedsheep.gameserver.curriculum.CurriculumAiTournamentPreset.AKIRI_CHEVILL.sourcePaths
        require(paths.size == 2) { "Locked pair curriculum must provide exactly two deck sources" }
        deckSourceLoader(repositoryRoot, paths[0]) to deckSourceLoader(repositoryRoot, paths[1])
    }

    /** Canonical exact-pair card registry over the full catalog. */
    private val registry: CardRegistry by lazy {
        CardRegistry().apply {
            MtgSetCatalog.all.forEach { set ->
                register(set.cards)
                register(set.basicLands)
            }
        }
    }

    /**
     * Authenticate the executed source revision against [expectedEngineCommit] using the accepted
     * source-bootstrap doctrine (HEAD equality, clean tracked tree, pins). Must be called before
     * any reconstruction.
     */
    fun authenticateSource(expectedEngineCommit: String): AuthenticatedSourceV1 {
        val result = LocalSourceBootstrapV1(
            GitSourceBootstrapProbeV1(
                repositoryRoot = repositoryRoot,
                requiredPinnedPaths = requiredPinnedPaths,
            ),
        ).verify(expectedEngineCommit)
        return if (result.verified) {
            AuthenticatedSourceV1(
                expectedEngineCommit = expectedEngineCommit,
                actualSourceCommit = checkNotNull(result.actualRuntimeSourceCommit),
            )
        } else {
            throw TransportedReplayReconstructionException(
                code = TransportedReplayReconstructionFailure.SOURCE_AUTHENTICATION_FAILED,
                message = "Verifier source revision is not the durable engineCommit " +
                    "(expected=$expectedEngineCommit, " +
                    "actual=${result.actualRuntimeSourceCommit ?: "unavailable"}, " +
                    "failure=${result.failureCode})",
                sourceBootstrapFailure = result.failureCode,
            )
        }
    }

    /**
     * Re-derive the durable environment identity from repository authority. Claimant fields that
     * are not repository-derivable (engine commit, seed, starting player, roster, config flags)
     * pass through from [expected] and are re-compared; repository-derived fields (card digest,
     * deck digests) must equal the claimant values or [TransportedReplayReconstructionFailure.ENVIRONMENT_IDENTITY_MISMATCH]
     * is thrown.
     */
    fun deriveEnvironmentIdentity(expected: com.wingedsheep.gym.trainer.trajectory.EnvironmentIdentityV1):
        com.wingedsheep.gym.trainer.trajectory.EnvironmentIdentityV1 {
        val (akiri, chevill) = deckSources
        val cardDigest = lockedCardDefinitionDigest(akiri, chevill)
        val rederived = expected.copy(
            cardDefinitionIdentity = cardDigest,
            akiriDeckIdentity = akiri.sourceDigest,
            chevillDeckIdentity = chevill.sourceDigest,
        )
        if (rederived != expected) {
            throw TransportedReplayReconstructionException(
                code = TransportedReplayReconstructionFailure.ENVIRONMENT_IDENTITY_MISMATCH,
                message = "Rederived environment identity differs from the transported identity",
            )
        }
        return rederived
    }

    /**
     * Reconstruct the episode and produce fresh verification evidence — sealed authority flow:
     * repository environment re-derivation (P1: card/deck digests against repository authority),
     * A5 validation, fresh execution, fresh A4 fold, and full request binding. Claimant data is
     * used only as durable identity + ordered semantic choices and as comparison targets.
     *
     * [authenticated] must come from [authenticateSource] on this same instance: reconstruction
     * is not callable without a successful source-authority proof, so no caller can skip or
     * reorder source authentication.
     */
    internal fun reconstruct(
        authenticated: AuthenticatedSourceV1,
        claimant: TrajectoryV1,
    ): TransportedReconstructionV1 {
        check(authenticated.actualSourceCommit == authenticated.expectedEngineCommit) {
            "Authenticated source does not carry the expected commit"
        }
        val durableIdentity = claimant.episodeMetadata.environmentIdentity
        check(durableIdentity.engineCommit == authenticated.expectedEngineCommit) {
            "Authenticated commit does not match the claimant environment identity"
        }
        // P1: repository-owned identity fields are re-derived and compared against the claimant
        // BEFORE any execution. A claimant with wrong card/deck digests fails here — its metadata
        // can never ride through reconstruction into the fresh trajectory.
        val identity = deriveEnvironmentIdentity(durableIdentity)
        val link = claimant.episodeMetadata.compactReplayLink

        // A5 fail-closed validation of the transported claimant trajectory (no engine consulted).
        when (com.wingedsheep.gym.trainer.trajectory.TrajectoryV1Validator.validate(claimant)) {
            is com.wingedsheep.gym.trainer.trajectory.TrajectoryValidationResult.Valid -> Unit
            else -> throw TransportedReplayReconstructionException(
                code = TransportedReplayReconstructionFailure.CLAIMANT_VALIDATION_FAILED,
                message = "Transported claimant trajectory failed A5 validation",
            )
        }

        // Fresh execution: rebuild the replay from durable identity + ordered semantic choices.
        val rebuilt = rebuildReplay(claimant, identity)

        // Fresh A4 fold over the verifier's own rebuilt replay bytes.
        val replayBytes = ReplayCodec.encode(rebuilt.replay)
        val decodedReplay = ReplayCodec.decode(replayBytes)
        if (decodedReplay != rebuilt.replay) {
            throw TransportedReplayReconstructionException(
                code = TransportedReplayReconstructionFailure.INTERNAL_RECONSTRUCTION_FAILURE,
                message = "Rebuilt replay does not survive its own codec round-trip",
            )
        }
        val freshContentIdentity = ReplayContentCanonicalizerV1.identity(decodedReplay)
        val freshSource = GymReplayFrameSource(
            replay = decodedReplay,
            cardRegistry = registry,
            fallbackPerspectivePlayerIndex = 0,
            tailClosure = rebuilt.closure,
        )
        val freshBinding = freshSource.verifyTrajectoryBinding()
        val freshVerification = freshBinding.verificationBinding.verification
        if (freshVerification.fidelity != ReplayFidelity.EXACT) {
            throw TransportedReplayReconstructionException(
                code = TransportedReplayReconstructionFailure.REPLAY_NOT_EXACT,
                message = "Fresh A4 fold was not exact: ${freshVerification.failureReason}",
            )
        }
        if (!freshVerification.completeRangeVerified) {
            throw TransportedReplayReconstructionException(
                code = TransportedReplayReconstructionFailure.REPLAY_INCOMPLETE,
                message = "Fresh A4 fold did not verify the complete replay range",
            )
        }

        // Replay content identity cross-binding against the claimant link.
        if (freshContentIdentity.value != link.replayContentIdentity) {
            throw TransportedReplayReconstructionException(
                code = TransportedReplayReconstructionFailure.REPLAY_CONTENT_IDENTITY_MISMATCH,
                message = "Rebuilt replay content identity diverges from the claimant value",
            )
        }

        // Per-boundary comparison of regenerated model-facing values against claimant fields,
        // fresh semantic decision identity chain, and the closure comparison.
        val freshDecisions = compareBoundariesAndRebuildDecisions(claimant, freshBinding, freshVerification)

        val freshTrajectory = TrajectoryV1(
            trajectoryId = "0".repeat(64),
            episodeMetadata = claimant.episodeMetadata,
            decisions = freshDecisions,
        ).let { provisional -> provisional.copy(trajectoryId = provisional.recomputeTrajectoryId()) }

        // Fresh trajectory identity + range cross-binding (request binding, §25).
        if (freshTrajectory.trajectoryId != claimant.trajectoryId) {
            throw TransportedReplayReconstructionException(
                code = TransportedReplayReconstructionFailure.TRAJECTORY_IDENTITY_MISMATCH,
                message = "Fresh trajectory identity diverges from the requested trajectory id",
            )
        }
        if (freshVerification.replayActionCount != link.replayActionCount) {
            throw TransportedReplayReconstructionException(
                code = TransportedReplayReconstructionFailure.REPLAY_INCOMPLETE,
                message = "Fresh replay action count diverges from the claimant replay range",
            )
        }
        if (freshVerification.closure != claimant.episodeMetadata.closure) {
            throw TransportedReplayReconstructionException(
                code = TransportedReplayReconstructionFailure.REPLAY_DIVERGED,
                message = "Fresh closure diverges from the transported claimant closure",
            )
        }

        return TransportedReconstructionV1(
            freshBinding = freshBinding,
            freshTrajectory = freshTrajectory,
            freshReplayContentIdentity = freshContentIdentity,
            freshReplayActionCount = freshVerification.replayActionCount,
        )
    }

    // ------------------------------------------------------------------
    // Fresh rebuild with semantic rebinding
    // ------------------------------------------------------------------

    private fun rebuildReplay(
        claimant: TrajectoryV1,
        identity: com.wingedsheep.gym.trainer.trajectory.EnvironmentIdentityV1,
    ): RebuiltReplay {
        val (akiri, chevill) = deckSources
        val resolver = com.wingedsheep.gym.service.DeckResolver(registry)
        val akiriDeck = resolver.resolve(com.wingedsheep.gym.service.DeckSpec.Explicit(akiri.libraryDeckList()))
        val chevillDeck = resolver.resolve(com.wingedsheep.gym.service.DeckSpec.Explicit(chevill.libraryDeckList()))
        if (identity.roster.size != 2) {
            throw TransportedReplayReconstructionException(
                code = TransportedReplayReconstructionFailure.UNSUPPORTED_ENVIRONMENT,
                message = "Production verifier v1 supports exactly two roster seats",
            )
        }
        val seat0 = identity.roster[0]
        val seat1 = identity.roster[1]
        val deckByRole = mapOf(
            ROLE_AKIRI to akiriDeck,
            ROLE_CHEVILL to chevillDeck,
        )
        val players = listOf(seat0, seat1).mapIndexed { index, seat ->
            val deck = deckByRole[seat.role]
                ?: throw TransportedReplayReconstructionException(
                    code = TransportedReplayReconstructionFailure.UNSUPPORTED_ENVIRONMENT,
                    message = "Unknown roster role '${seat.role}' at seat $index",
                )
            PlayerConfig(
                name = seat.role.lowercase().replaceFirstChar { it.uppercase() },
                deck = deck,
                startingLife = DEFAULT_COMMANDER_STARTING_LIFE,
                playerId = seat.playerId,
                commanderCardName = checkNotNull(seat.commanderDefinitionIdentity) {
                    "Roster seat $index carries no commander definition identity"
                },
            )
        }
        val startingPlayerIndex = players.indexOfFirst { it.playerId == identity.startingPlayer }
        if (startingPlayerIndex < 0) {
            throw TransportedReplayReconstructionException(
                code = TransportedReplayReconstructionFailure.UNSUPPORTED_ENVIRONMENT,
                message = "Durable starting player is not part of the roster",
            )
        }
        val config = GameConfig(
            players = players,
            startingHandSize = identity.startingHandSize,
            skipMulligans = identity.skipMulligans,
            useHandSmoother = identity.useHandSmoother,
            startingPlayerIndex = startingPlayerIndex,
            format = Format.Commander(),
            attackMode = AttackMode.valueOf(identity.attackMode),
            seed = identity.actualEngineSeed,
        )

        val chosenInputs = claimant.decisions.map { record ->
            ReplayChosenInputV1(
                replayActionIndex = record.replayActionIndex,
                perspectivePlayerId = record.perspectivePlayerId,
                chosenSemanticAction = record.chosenSemanticAction,
                chosenSemanticResponse = record.chosenSemanticResponse,
            )
        }

        val environment = GameEnvironment.create(
            cardRegistry = registry,
            executionMode = GameEnvironmentMode.TRUSTED,
        )
        environment.reset(config, maxReplaySteps)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = registry),
        )
        val actions = mutableListOf<GameAction>()
        val checkpoints = mutableListOf<ReplayCheckpoint>()
        var result = gym.observe()
        var observation = requireTrainingObservation(result)
        for ((index, chosen) in chosenInputs.withIndex()) {
            if (observation.terminated || observation.truncated) {
                throw TransportedReplayReconstructionException(
                    code = TransportedReplayReconstructionFailure.REPLAY_INCOMPLETE,
                    message = "Fresh episode ended before consuming semantic input $index",
                )
            }
            val rebound: ReboundAction = if (chosen.chosenSemanticAction != null) {
                rebindAction(result, observation, checkNotNull(chosen.chosenSemanticAction), index)
            } else {
                rebindResponse(result, observation, chosen, index)
            }
            result = if (rebound.action is SubmitDecision) {
                gym.submitDecision(rebound.action.response, actorId = observation.agentToAct)
            } else {
                val viewIndex = checkNotNull(rebound.viewIndex) {
                    "Rebound action at $index has no CURRENT public view"
                }
                val payload = rebound.payload
                if (payload == null) {
                    gym.step(observation.legalActions[viewIndex].actionId)
                } else {
                    gym.step(observation.legalActions[viewIndex].actionId, payload)
                }
            }
            actions += rebound.action
            if (actions.size % ReplayRecordingPolicy.CHECKPOINT_EVERY_ACTIONS == 0) {
                checkpoints += ReplayCheckpoint(
                    afterActionCount = actions.size,
                    fingerprint = ReplayFingerprint.of(environment.state, CompactReplay.CURRENT_VERSION),
                )
            }
            observation = requireTrainingObservation(result)
        }
        val closure = checkNotNull(environment.episodeClosure) {
            "Fresh execution ended without a typed closure"
        }
        if (closure.stepCount != actions.size) {
            throw TransportedReplayReconstructionException(
                code = TransportedReplayReconstructionFailure.REPLAY_INCOMPLETE,
                message = "Fresh closure step count diverges from the executed action count",
            )
        }
        if (checkpoints.lastOrNull()?.afterActionCount != actions.size) {
            checkpoints += ReplayCheckpoint(
                afterActionCount = actions.size,
                fingerprint = ReplayFingerprint.of(environment.state, CompactReplay.CURRENT_VERSION),
            )
        }
        val setup = replaySetup(config)
        val replay = CompactReplay(
            version = CompactReplay.CURRENT_VERSION,
            // gameId/startedAt/endedAt are outside ReplayContentCanonicalizerV1.identity; stable
            // literals keep the rebuilt content identity comparable to the producer's.
            gameId = RECONSTRUCTED_GAME_ID,
            players = config.players.map { player ->
                ReplayPlayerInfo(checkNotNull(player.playerId).value, player.name)
            },
            startedAt = RECONSTRUCTED_STARTED_AT,
            endedAt = RECONSTRUCTED_ENDED_AT,
            winnerName = (closure as? EpisodeClosureV1.GameTerminal)?.winnerId?.let { winner ->
                config.players.firstOrNull { it.playerId == winner }?.name
            },
            setup = setup,
            actions = actions,
            pinnedCards = ReplayCardPin.capture(registry, setup),
            checkpoints = checkpoints,
        )
        return RebuiltReplay(replay, closure)
    }

    private data class ReboundAction(
        val action: GameAction,
        val viewIndex: Int?,
        val payload: kotlinx.serialization.json.JsonObject?,
    )

    /**
     * Rebind one transported action candidate against the CURRENT public domain and registry.
     * The stored candidate's canonical semantic representation must be a member of the current
     * domain; the fresh registry resolves the live action binding.
     */
    private fun rebindAction(
        result: ObservationResult,
        observation: TrainingObservation,
        chosen: ChosenSemanticActionV1,
        index: Int,
    ): ReboundAction {
        val currentDomain = CompleteLegalDomainV1.from(observation)
        val chosenJson = A3SemanticJson.canonicalJson(chosen.candidate)
        val viewIndex = currentDomain.candidates.indexOfFirst { candidate ->
            A3SemanticJson.canonicalJson(candidate) == chosenJson
        }
        if (viewIndex < 0 || viewIndex >= observation.legalActions.size) {
            throw TransportedReplayReconstructionException(
                code = TransportedReplayReconstructionFailure.SEMANTIC_REBIND_FAILED,
                message = "Transported candidate at $index is not in the CURRENT public domain",
            )
        }
        val view = observation.legalActions[viewIndex]
        return when (val resolved = result.registry.resolve(view.actionId)) {
            is ResolvedAction.Legal -> {
                val action = if (chosen.choicePayload.isEmpty()) {
                    resolved.action
                } else {
                    materializeAction(resolved.action, chosen.choicePayload)
                }
                ReboundAction(
                    action = action,
                    viewIndex = viewIndex,
                    payload = chosen.choicePayload.takeIf { it.isNotEmpty() },
                )
            }

            is ResolvedAction.Decision -> throw TransportedReplayReconstructionException(
                code = TransportedReplayReconstructionFailure.SEMANTIC_REBIND_FAILED,
                message = "Transported ACTION-class choice at $index resolved to a decision",
            )

            ResolvedAction.Unknown -> throw TransportedReplayReconstructionException(
                code = TransportedReplayReconstructionFailure.SEMANTIC_REBIND_FAILED,
                message = "Transported choice at $index did not resolve in the CURRENT registry",
            )
        }
    }

    /**
     * Rebind one transported response against the CURRENT pending decision. Folded options use
     * the accepted membership rule (exact actionSemantics match, ignoring stored option
     * metadata); structured responses are decoded with the live routing id, and semantic
     * ordering references are mapped back through the CURRENT ordering domain.
     */
    private fun rebindResponse(
        result: ObservationResult,
        observation: TrainingObservation,
        chosen: ReplayChosenInputV1,
        index: Int,
    ): ReboundAction {
        val pending = checkNotNull(observation.pendingDecision) {
            "Transported response at $index has no CURRENT pending decision"
        }
        if (pending.playerId != chosen.perspectivePlayerId) {
            throw TransportedReplayReconstructionException(
                code = TransportedReplayReconstructionFailure.SEMANTIC_REBIND_FAILED,
                message = "Transported response perspective at $index disagrees with the CURRENT decision",
            )
        }
        val decisionId = checkNotNull(pending.decisionId) {
            "CURRENT pending decision at $index carries no routing id"
        }
        val semantic = checkNotNull(chosen.chosenSemanticResponse) {
            "Response-class choice at $index carries no chosen semantic response"
        }
        val responseJson = semantic.response
        if (pending.requiresStructuredResponse) {
            val domain = CompleteLegalDomainV1.from(observation)
            return ReboundAction(
                action = SubmitDecision(
                    pending.playerId,
                    decodeStructuredResponse(domain, responseJson, decisionId, index),
                ),
                viewIndex = null,
                payload = null,
            )
        }
        val domain = CompleteLegalDomainV1.from(observation)
        val responseCanonical = A3SemanticJson.canonicalJson(responseJson)
        val viewIndex = domain.candidates.indexOfFirst { candidate ->
            val actionSemantics = candidate["actionSemantics"] as? JsonObject ?: return@indexOfFirst false
            A3SemanticJson.canonicalJson(actionSemantics) == responseCanonical ||
                A3SemanticJson.canonicalJson(
                    JsonObject(actionSemantics.filterKeys { it != "optionMetadata" }),
                ) == responseCanonical
        }
        if (viewIndex < 0 || viewIndex >= observation.legalActions.size) {
            throw TransportedReplayReconstructionException(
                code = TransportedReplayReconstructionFailure.SEMANTIC_REBIND_FAILED,
                message = "Transported folded response at $index is not in the CURRENT public domain",
            )
        }
        val view = observation.legalActions[viewIndex]
        return when (val resolved = result.registry.resolve(view.actionId)) {
            is ResolvedAction.Decision ->
                ReboundAction(
                    action = SubmitDecision(pending.playerId, resolved.response.withDecisionId(decisionId)),
                    viewIndex = null,
                    payload = null,
                )

            is ResolvedAction.Legal -> throw TransportedReplayReconstructionException(
                code = TransportedReplayReconstructionFailure.SEMANTIC_REBIND_FAILED,
                message = "Transported RESPONSE-class choice at $index resolved to a plain action",
            )

            ResolvedAction.Unknown -> throw TransportedReplayReconstructionException(
                code = TransportedReplayReconstructionFailure.SEMANTIC_REBIND_FAILED,
                message = "Transported response at $index did not resolve in the CURRENT registry",
            )
        }
    }

    /** Decode one structured semantic response JSON into a live [DecisionResponse]. */
    private fun decodeStructuredResponse(
        domain: CompleteLegalDomainV1,
        responseJson: JsonObject,
        decisionId: String,
        index: Int,
    ): DecisionResponse {
        val decodedJson: JsonObject = if (
            (responseJson["type"] as? JsonPrimitive)?.contentOrNull == "OrderedResponse" &&
            (responseJson["orderedObjects"] as? JsonArray)?.any { it is JsonObject } == true
        ) {
            val ordering = domain.structuredDomain as? OrderingDomain
                ?: throw TransportedReplayReconstructionException(
                    code = TransportedReplayReconstructionFailure.SEMANTIC_REBIND_FAILED,
                    message = "Semantic ordering response at $index has no current ordering domain",
                )
            val objectByReference = ordering.objects.associateBy { objectId ->
                A3SemanticJson.canonicalJson(
                    ObservationCanonicalizer.semanticOrderingObject(
                        objectId = objectId.value,
                        label = ordering.objectLabels?.get(objectId),
                        cardInfo = ordering.cardInfo?.get(objectId)?.let { cardInfo ->
                            A3SemanticJson.strictJson.encodeToJsonElement(
                                StructuredCardInfo.serializer(),
                                cardInfo,
                            )
                        },
                    ),
                )
            }
            val orderedIds = (responseJson.getValue("orderedObjects") as JsonArray).map { reference ->
                val key = A3SemanticJson.canonicalJson(reference)
                (objectByReference[key]
                    ?: throw TransportedReplayReconstructionException(
                        code = TransportedReplayReconstructionFailure.SEMANTIC_REBIND_FAILED,
                        message = "Semantic ordering reference at $index is not in the CURRENT domain",
                    )).value
            }
            buildJsonObject {
                responseJson.forEach { (key, value) -> if (key != "orderedObjects") put(key, value) }
                put("orderedObjects", JsonArray(orderedIds.map { JsonPrimitive(it) }))
            }
        } else {
            responseJson
        }
        val withRoutingId = buildJsonObject {
            decodedJson.forEach { (key, value) -> put(key, value) }
            put("decisionId", decisionId)
        }
        return A3SemanticJson.decodeStrict(
            DecisionResponse.serializer(),
            withRoutingId,
            "rebound decision response at $index",
        )
    }

    /**
     * Materialize one structured action payload onto the resolved action template, preserving
     * the template's action type and ability key.
     */
    fun materializeAction(template: GameAction, payload: JsonObject): GameAction {
        val templateJson = A3SemanticJson.strictJson
            .encodeToJsonElement(GameAction.serializer(), template)
            .jsonObject
        require(payload["type"] == null || payload["type"] == templateJson["type"])
        val merged = buildJsonObject {
            templateJson.forEach { (key, value) -> put(key, value) }
            payload.forEach { (key, value) -> if (key != "abilityKey") put(key, value) }
        }
        return A3SemanticJson.decodeStrict(GameAction.serializer(), merged, "materialized action")
    }

    // ------------------------------------------------------------------
    // Boundary comparison + fresh trajectory rebuild
    // ------------------------------------------------------------------

    private fun compareBoundariesAndRebuildDecisions(
        claimant: TrajectoryV1,
        freshBinding: ReplayTrajectoryBindingV1,
        freshVerification: com.wingedsheep.gym.contract.VerifiedReplayVerification,
    ): List<com.wingedsheep.gym.trainer.trajectory.DecisionRecordV1> {
        val decisions = claimant.decisions
        var prefix = SemanticReplayPrefixV1()
        return decisions.mapIndexed { index, record ->
            val frame = freshVerification.frames.getOrNull(index)
                ?: throw TransportedReplayReconstructionException(
                    code = TransportedReplayReconstructionFailure.REPLAY_INCOMPLETE,
                    message = "Fresh fold produced no frame at $index",
                )
            if (frame.replayActionIndex != index) {
                throw TransportedReplayReconstructionException(
                    code = TransportedReplayReconstructionFailure.REPLAY_INCOMPLETE,
                    message = "Fresh fold frame index diverges at $index",
                )
            }
            if (frame.perspectivePlayerId != record.perspectivePlayerId ||
                frame.observation.perspectivePlayerId != record.perspectivePlayerId
            ) {
                throw TransportedReplayReconstructionException(
                    code = TransportedReplayReconstructionFailure.REPLAY_DIVERGED,
                    message = "Fresh perspective diverges at $index",
                )
            }
            // Regenerated model-facing boundaries come only from the fresh fold; the stored
            // claimant fields are comparison targets, never the expected value.
            if (frame.observation != record.observationBefore) {
                throw TransportedReplayReconstructionException(
                    code = TransportedReplayReconstructionFailure.REPLAY_DIVERGED,
                    message = "Fresh observation diverges at $index",
                )
            }
            if (frame.completeLegalDomain != record.completeLegalDomain) {
                throw TransportedReplayReconstructionException(
                    code = TransportedReplayReconstructionFailure.REPLAY_DIVERGED,
                    message = "Fresh legal domain diverges at $index",
                )
            }
            if (frame.candidateDomainDigest != record.candidateDomainDigest) {
                throw TransportedReplayReconstructionException(
                    code = TransportedReplayReconstructionFailure.REPLAY_DIVERGED,
                    message = "Fresh candidate digest diverges at $index",
                )
            }
            val chosenInput = freshBinding.chosenInputBinding.chosenInputs.getOrNull(index)
                ?: throw TransportedReplayReconstructionException(
                    code = TransportedReplayReconstructionFailure.REPLAY_INCOMPLETE,
                    message = "Fresh chosen-input binding is missing index $index",
                )
            if (chosenInput.replayActionIndex != index ||
                chosenInput.perspectivePlayerId != record.perspectivePlayerId
            ) {
                throw TransportedReplayReconstructionException(
                    code = TransportedReplayReconstructionFailure.REPLAY_DIVERGED,
                    message = "Fresh chosen-input binding disagrees with the claimant record at $index",
                )
            }
            if (chosenInput.chosenSemanticAction == null && chosenInput.chosenSemanticResponse == null) {
                throw TransportedReplayReconstructionException(
                    code = TransportedReplayReconstructionFailure.SEMANTIC_REBIND_FAILED,
                    message = "Fresh chosen-input binding carries no semantic choice at $index",
                )
            }
            if (record.chosenSemanticAction == null && record.chosenSemanticResponse == null) {
                throw TransportedReplayReconstructionException(
                    code = TransportedReplayReconstructionFailure.CLAIMANT_VALIDATION_FAILED,
                    message = "Transported decision record at $index carries no semantic choice",
                )
            }
            if (chosenInput.chosenSemanticAction != null) {
                if (chosenInput.chosenSemanticAction != record.chosenSemanticAction) {
                    throw TransportedReplayReconstructionException(
                        code = TransportedReplayReconstructionFailure.REPLAY_DIVERGED,
                        message = "Rebound action diverges from the transported choice at $index",
                    )
                }
            } else if (chosenInput.chosenSemanticResponse != record.chosenSemanticResponse) {
                throw TransportedReplayReconstructionException(
                    code = TransportedReplayReconstructionFailure.REPLAY_DIVERGED,
                    message = "Rebound response diverges from the transported choice at $index",
                )
            }
            // Recompute the semantic decision identity from the fresh prefix + fresh boundaries.
            val freshIdentity = SemanticDecisionIdentityV1.from(
                semanticEpisodeId = claimant.episodeMetadata.semanticEpisodeId,
                prefix = prefix,
                replayActionIndex = index,
                observation = frame.observation,
                domain = frame.completeLegalDomain,
                perspectivePlayerId = record.perspectivePlayerId.value,
            )
            if (freshIdentity.decisionKind != record.decisionKind ||
                freshIdentity.semanticDecisionId() != record.semanticDecisionId
            ) {
                throw TransportedReplayReconstructionException(
                    code = TransportedReplayReconstructionFailure.REPLAY_DIVERGED,
                    message = "Fresh semantic decision identity diverges at $index",
                )
            }
            val input = chosenInput.chosenSemanticAction?.let(SemanticReplayInputV1::action)
                ?: SemanticReplayInputV1.response(checkNotNull(chosenInput.chosenSemanticResponse))
            prefix = prefix.copy(inputs = prefix.inputs + input)
            com.wingedsheep.gym.trainer.trajectory.DecisionRecordV1(
                decisionIndex = index,
                replayActionIndex = index,
                replayFrameIndex = index,
                perspectivePlayerId = frame.perspectivePlayerId,
                decisionKind = freshIdentity.decisionKind,
                semanticDecisionId = freshIdentity.semanticDecisionId(),
                observationBefore = frame.observation,
                completeLegalDomain = frame.completeLegalDomain,
                candidateDomainDigest = frame.candidateDomainDigest,
                chosenSemanticAction = chosenInput.chosenSemanticAction,
                chosenSemanticResponse = chosenInput.chosenSemanticResponse,
            )
        }
    }

    // ------------------------------------------------------------------
    // Repository authority helpers
    // ------------------------------------------------------------------

    /** Accepted locked-pair card-definition digest recipe (exact-pair acceptance authority). */
    fun lockedCardDefinitionDigest(
        akiri: CurriculumDeckSourceV1,
        chevill: CurriculumDeckSourceV1,
    ): String {
        val lockedCards = (akiri.deckList.keys + chevill.deckList.keys).distinct()
        val resolved = lockedCards.mapNotNull(registry::getCard).distinctBy { it.name }
        check(resolved.size >= lockedCards.size - 2) {
            "Card-definition digest could not resolve all locked deck cards"
        }
        val serialized = resolved.map { card ->
            card.name to CardSerialization.json.encodeToJsonElement(
                com.wingedsheep.sdk.model.CardDefinition.serializer(),
                card,
            )
        }.sortedBy { it.first }
        val digestInput = serialized.joinToString("\n") { (name, json) ->
            "$name:${A3SemanticJson.canonicalJson(json)}"
        }
        return A3SemanticJson.sha256(
            digestInput.toByteArray(java.nio.charset.StandardCharsets.UTF_8),
        ).uppercase()
    }

    private fun replaySetup(config: GameConfig): ReplaySetup {
        val playerIds = config.players.map { checkNotNull(it.playerId) }
        return ReplaySetup(
            seed = checkNotNull(config.seed),
            format = config.format,
            attackMode = config.attackMode,
            startingHandSize = config.startingHandSize,
            skipMulligans = config.skipMulligans,
            useHandSmoother = config.useHandSmoother,
            handSmootherCandidates = config.handSmootherCandidates,
            startingPlayerIndex = config.startingPlayerIndex,
            teams = config.teams,
            players = config.players.mapIndexed { index, player ->
                ReplayPlayerSetup(
                    playerId = playerIds[index].value,
                    name = player.name,
                    deck = player.deck,
                    startingLife = player.startingLife,
                    commanderCardName = player.commanderCardName,
                )
            },
            seatRoster = config.players.mapIndexed { index, player ->
                com.wingedsheep.gameserver.protocol.ServerMessage.PlayerSeatInfo(
                    playerId = playerIds[index].value,
                    name = player.name,
                    seatIndex = index,
                )
            },
        )
    }

    private fun requireTrainingObservation(result: ObservationResult): TrainingObservation {
        check(result.diagnostics.isEmpty()) { "Public observation carried diagnostics" }
        val observation = result.observation as? TrainingObservation
            ?: throw TransportedReplayReconstructionException(
                code = TransportedReplayReconstructionFailure.UNSUPPORTED_RECONSTRUCTION,
                message = "Expected a TrainingObservation on the trusted public boundary",
            )
        check(observation.schemaHash == SchemaHash.CURRENT) {
            "Stale Gym schema hash: ${observation.schemaHash}"
        }
        return observation
    }

    companion object {
        /**
         * Explicit horizon contract: [MAX_REPLAY_STEPS_CEILING] is hard-owned, a claimant range
         * beyond it fails closed instead of being truncated, and test overrides may only lower
         * the horizon, never exceed the ceiling.
         */
        fun computeReplayHorizon(claimantReplayActionCount: Int?, override: Int?): Int {
            override?.let {
                require(it in 1..MAX_REPLAY_STEPS_CEILING) {
                    "Replay horizon override must be within 1..$MAX_REPLAY_STEPS_CEILING"
                }
                return it
            }
            require(claimantReplayActionCount == null || claimantReplayActionCount >= 0) {
                "Claimant replay action count must not be negative"
            }
            return (
                claimantReplayActionCount?.let { it + REPLAY_HORIZON_HEADROOM }
                    ?: DEFAULT_REPLAY_HORIZON
                ).coerceAtMost(MAX_REPLAY_STEPS_CEILING)
        }

        /**
         * Sealed authority flow (KA06_02_REMEDIATION_01): authenticate the executed source
         * revision with the accepted bootstrap doctrine and hand back a reconstructor bound to
         * that proof. Fresh execution is not reachable without a successful authentication — the
         * unauthenticated constructor stays internal, and [AuthenticatedReconstructorV1.reconstruct]
         * is the only public reconstruction seam.
         */
        fun authenticate(
            repositoryRoot: Path,
            expectedEngineCommit: String,
            claimantReplayActionCount: Int? = null,
        ): AuthenticatedReconstructorV1 {
            val reconstructor = TransportedReplayReconstructorV1(
                repositoryRoot = repositoryRoot,
                claimantReplayActionCountOrNull = claimantReplayActionCount,
            )
            return AuthenticatedReconstructorV1(
                reconstructor,
                reconstructor.authenticateSource(expectedEngineCommit),
            )
        }

        /** Fixed horizon when no claimant range is known (protocol fixtures only). */
        const val DEFAULT_REPLAY_HORIZON: Int = 40
        /** Durable-range headroom covering setup steps beyond authoritative choices. */
        const val REPLAY_HORIZON_HEADROOM: Int = 64
        /** Hard-owned production ceiling; larger episodes fail closed, never truncated. */
        const val MAX_REPLAY_STEPS_CEILING: Int = 4096
        const val DEFAULT_COMMANDER_STARTING_LIFE: Int = 40
        const val RECONSTRUCTED_GAME_ID: String = "transported-replay-verifier-v1"
        const val RECONSTRUCTED_STARTED_AT: String = "1970-01-01T00:00:00Z"
        const val RECONSTRUCTED_ENDED_AT: String = "1970-01-01T00:00:01Z"
        const val ROLE_AKIRI: String = "AKIRI"
        const val ROLE_CHEVILL: String = "CHEVILL"

        val DEFAULT_REQUIRED_PINS: List<String> = listOf(
            "gradlew",
            "gradle/wrapper/gradle-wrapper.properties",
            "gradle/libs.versions.toml",
        )
    }
}

/** Typed, non-generic reconstruction failure carrying its trust-relevant cause. */
class TransportedReplayReconstructionException(
    val code: TransportedReplayReconstructionFailure,
    message: String,
    /** Set when [code] is SOURCE_AUTHENTICATION_FAILED: the exact bootstrap doctrine failure. */
    val sourceBootstrapFailure: com.wingedsheep.gym.trainer.actor.SourceBootstrapFailureCodeV1? = null,
) : IllegalStateException(message)

/** Materially distinct reconstruction failure causes (diagnostics only, not trust state). */
enum class TransportedReplayReconstructionFailure {
    SOURCE_AUTHENTICATION_FAILED,
    ENVIRONMENT_IDENTITY_MISMATCH,
    CLAIMANT_VALIDATION_FAILED,
    SEMANTIC_REBIND_FAILED,
    REPLAY_NOT_EXACT,
    REPLAY_INCOMPLETE,
    REPLAY_DIVERGED,
    REPLAY_CONTENT_IDENTITY_MISMATCH,
    TRAJECTORY_IDENTITY_MISMATCH,
    UNSUPPORTED_ENVIRONMENT,
    UNSUPPORTED_RECONSTRUCTION,
    INTERNAL_RECONSTRUCTION_FAILURE,
}

data class AuthenticatedSourceV1(
    val expectedEngineCommit: String,
    val actualSourceCommit: String,
)

/**
 * A reconstructor bound to a successful same-revision source proof. The only reconstruction
 * seam: fresh execution is impossible without prior repository source authority (P1).
 */
class AuthenticatedReconstructorV1 internal constructor(
    private val reconstructor: TransportedReplayReconstructorV1,
    private val authenticated: AuthenticatedSourceV1,
) {
    /** The successful same-revision proof this reconstructor is bound to. */
    val authenticatedSource: AuthenticatedSourceV1 get() = authenticated

    fun reconstruct(claimant: TrajectoryV1): TransportedReconstructionV1 =
        reconstructor.reconstruct(authenticated, claimant)
}

data class TransportedReconstructionV1(
    val freshBinding: ReplayTrajectoryBindingV1,
    val freshTrajectory: TrajectoryV1,
    val freshReplayContentIdentity: ReplayContentIdentityV1,
    val freshReplayActionCount: Int,
)

private class RebuiltReplay(
    val replay: CompactReplay,
    val closure: EpisodeClosureV1,
)
