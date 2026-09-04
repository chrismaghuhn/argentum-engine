package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.handlers.actions.decision.DecisionValidators
import com.wingedsheep.engine.legalactions.EnumerationMode
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.LegalActionEnumerator
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.registry.PrintingRegistry
import com.wingedsheep.engine.registry.TokenArtRegistry
import com.wingedsheep.gym.GameActionCandidateMatcher
import com.wingedsheep.gym.EpisodeClosureV1
import com.wingedsheep.gym.ObservationPerspective
import com.wingedsheep.gym.contract.ActionPayloadRequirements
import com.wingedsheep.gym.contract.AttackDeclarationDomainSubmission
import com.wingedsheep.gym.contract.BlockerDeclarationDomainSubmission
import com.wingedsheep.gym.contract.CandidateDomainDigestV1
import com.wingedsheep.gym.contract.ChosenSemanticActionV1
import com.wingedsheep.gym.contract.ChosenSemanticResponseV1
import com.wingedsheep.gym.contract.CompleteLegalDomainV1
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.PlayerObservationV1
import com.wingedsheep.gym.contract.ReplayChosenInputBindingSource
import com.wingedsheep.gym.contract.ReplayChosenInputBindingV1
import com.wingedsheep.gym.contract.ReplayChosenInputV1
import com.wingedsheep.gym.contract.ReplayTrajectoryBindingSource
import com.wingedsheep.gym.contract.ReplayTrajectoryBindingV1
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.contract.ManaColorDomainSubmission
import com.wingedsheep.gym.contract.VerifiedReplayFrame
import com.wingedsheep.gym.contract.VerifiedReplayFrameSource
import com.wingedsheep.gym.contract.VerifiedReplayVerification
import com.wingedsheep.gym.contract.ReplayVerificationBindingSource
import com.wingedsheep.gym.contract.ReplayVerificationBindingV1
import com.wingedsheep.gym.contract.ReplayFidelity as VerifiedReplayFidelity
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.gym.ActionPaymentPlanValidator
import kotlinx.serialization.json.JsonObject

/**
 * Game-server adapter that exposes one CompactReplay as verified Gym public boundaries.
 *
 * The adapter owns no replay fold. [ReplayReconstructor] remains the only action executor; the
 * existing [LegalActionEnumerator] and [GameActionCandidateMatcher] provide the public candidate
 * surface and its membership normalization. No [com.wingedsheep.engine.state.GameState] crosses
 * this class's [VerifiedReplayFrameSource] boundary. CompactReplay does not encode whether a
 * nonterminal tail stopped at the configured horizon, so [tailClosure] is required authoritative
 * composition-root evidence and is checked against the reconstructed tail before exactness.
 */
class GymReplayFrameSource(
    private val replay: CompactReplay,
    cardRegistry: CardRegistry,
    private val printingRegistry: PrintingRegistry? = null,
    private val tokenArtRegistry: TokenArtRegistry? = null,
    private val fallbackPerspectivePlayerIndex: Int = 0,
    /** Lifecycle evidence supplied by the composition root for this replay's inclusive tail. */
    private val tailClosure: EpisodeClosureV1,
) : VerifiedReplayFrameSource, ReplayVerificationBindingSource, ReplayChosenInputBindingSource,
    ReplayTrajectoryBindingSource {

    private val replayPlayerIds = replay.setup.players.map { EntityId(it.playerId) }
    private val replayContentIdentity by lazy { ReplayContentCanonicalizerV1.identity(replay) }
    private val replayRegistry = ReplayCardPin.overlay(cardRegistry, replay.pinnedCards)
    private val observationBuilder = ObservationBuilder(cardRegistry = replayRegistry)
    private val legalActionEnumerator = LegalActionEnumerator.create(replayRegistry)
    private val reconstructor = ReplayReconstructor(
        cardRegistry = cardRegistry,
        printingRegistry = printingRegistry,
        tokenArtRegistry = tokenArtRegistry,
    )

    init {
        require(replayPlayerIds.isNotEmpty()) {
            "Verified replay requires at least one setup player"
        }
        require(replayPlayerIds.distinct().size == replayPlayerIds.size) {
            "Verified replay setup contains duplicate player identities"
        }
        require(fallbackPerspectivePlayerIndex in replayPlayerIds.indices) {
            "Fallback perspective index $fallbackPerspectivePlayerIndex is outside the replay seat list"
        }
    }

    override fun verify(): VerifiedReplayVerification = verifyInternal()

    override fun verifyBinding(): ReplayVerificationBindingV1 = ReplayVerificationBindingV1(
        replayContentIdentity = replayContentIdentity,
        verification = verifyInternal(),
    )

    override fun verifyChosenInputBinding(): ReplayChosenInputBindingV1 {
        return verifyWithChosenInputs().chosenInputBinding
    }

    override fun verifyTrajectoryBinding(): ReplayTrajectoryBindingV1 = verifyWithChosenInputs()

    private fun verifyWithChosenInputs(): ReplayTrajectoryBindingV1 {
        val chosenInputs = mutableListOf<ReplayChosenInputV1>()
        val verification = verifyInternal { chosenInputs += it }
        require(chosenInputs.size == replay.actions.size) {
            "Replay chosen-input binding did not cover every replay action: " +
                verification.failureReason
        }
        return ReplayTrajectoryBindingV1(
            verificationBinding = ReplayVerificationBindingV1(
                replayContentIdentity = replayContentIdentity,
                verification = verification,
            ),
            chosenInputBinding = ReplayChosenInputBindingV1(
                replayContentIdentity = replayContentIdentity,
                replayActionCount = replay.actions.size,
                chosenInputs = chosenInputs,
            ),
        )
    }

    private fun verifyInternal(
        chosenInputConsumer: ((ReplayChosenInputV1) -> Unit)? = null,
    ): VerifiedReplayVerification {
        if (replay.version != CompactReplay.CURRENT_VERSION) {
            return failure(
                fidelity = VerifiedReplayFidelity.DIVERGED,
                reason = "Unsupported CompactReplay version ${replay.version}; " +
                    "supported through ${CompactReplay.CURRENT_VERSION}",
                failureAt = 0.takeIf { replay.actions.isNotEmpty() },
            )
        }

        val frames = mutableListOf<VerifiedReplayFrame>()
        var currentBoundary: PublicBoundary? = null
        val forward = try {
            reconstructor.replayForward(
                replay = replay,
                onFrame = { afterActionCount, state ->
                    val boundary = buildBoundary(
                        afterActionCount = afterActionCount,
                        state = state,
                        includeChosenInput = chosenInputConsumer != null,
                    )
                    require(afterActionCount == frames.size) {
                        "Replay public frame coordinate skipped from ${frames.size} to $afterActionCount"
                    }
                    frames += boundary.frame
                    currentBoundary = boundary
                },
                onBeforeAction = { index, state, action ->
                    val boundary = checkNotNull(currentBoundary) {
                        "Replay action $index has no preceding public boundary"
                    }
                    require(boundary.frame.replayActionIndex == index) {
                        "Replay action $index does not match its public boundary coordinate"
                    }
                    verifyRecordedAction(
                        index = index,
                        state = state,
                        boundary = boundary,
                        action = action,
                        includeChosenInput = chosenInputConsumer != null,
                    )
                        ?.let { chosenInputConsumer?.invoke(it) }
                },
            )
        } catch (exception: Exception) {
            return failure(
                fidelity = VerifiedReplayFidelity.DIVERGED,
                reason = "Replay forward fold failed: ${exception.message}",
                failureAt = 0.takeIf { replay.actions.isNotEmpty() },
            )
        }

        val shapeFailure = checkpointShapeFailure() ?: replayInputShapeFailure()
        val tailBoundary = currentBoundary?.takeIf {
            it.frame.replayActionIndex == replay.actions.size
        }
        val closureFailure = tailBoundary?.let(::tailClosureFailure)
        val verifiedClosure = tailBoundary?.let { verifiedTailClosure() }
        // CompactReplay v5 records cadence checkpoints plus a persistence tail.  The initial
        // public boundary is authoritative even when v5 has no persisted checkpoint at count 0;
        // if a producer did persist one, ReplayReconstructor has already checked its fingerprint.
        // The zero-action replay is the one exception: its tail is also its initial checkpoint.
        val initialCheckpointCount = replay.checkpoints.count { it.afterActionCount == 0 }
        val initialCheckpointVerified = when {
            replay.actions.isEmpty() -> forward.initialCheckpointVerified && initialCheckpointCount == 1
            initialCheckpointCount == 0 -> true
            initialCheckpointCount == 1 -> forward.initialCheckpointVerified
            else -> false
        }
        val tailCheckpointVerified = forward.tailCheckpointVerified &&
            replay.checkpoints.count { it.afterActionCount == replay.actions.size } == 1
        val intermediateCheckpointsVerified = forward.intermediateCheckpointsVerified &&
            replay.checkpoints.none { it.afterActionCount !in 0..replay.actions.size } &&
            replay.checkpoints.groupingBy { it.afterActionCount }.eachCount().values.all { it == 1 } &&
            replay.checkpoints
                .map { it.afterActionCount }
                .filter { it in 1 until replay.actions.size }
                .sorted() == expectedCheckpointCounts().filter {
                it in 1 until replay.actions.size
            }

        val fidelity = when {
            forward.fidelity == ReplayFidelity.DIVERGED -> VerifiedReplayFidelity.DIVERGED
            shapeFailure != null -> VerifiedReplayFidelity.UNVERIFIED
            closureFailure != null -> VerifiedReplayFidelity.DIVERGED
            forward.fidelity != ReplayFidelity.EXACT -> forward.fidelity.toVerified()
            forward.appliedActionCount != replay.actions.size -> VerifiedReplayFidelity.DIVERGED
            frames.size != replay.actions.size + 1 -> VerifiedReplayFidelity.DIVERGED
            !initialCheckpointVerified || !intermediateCheckpointsVerified || !tailCheckpointVerified ->
                VerifiedReplayFidelity.UNVERIFIED
            else -> VerifiedReplayFidelity.EXACT
        }

        return VerifiedReplayVerification(
            replayVersion = replay.version,
            replayActionCount = replay.actions.size,
            verifiedActionCount = forward.appliedActionCount,
            fidelity = fidelity,
            frames = frames.toList(),
            initialCheckpointVerified = initialCheckpointVerified,
            intermediateCheckpointsVerified = intermediateCheckpointsVerified,
            tailCheckpointVerified = tailCheckpointVerified,
            closure = verifiedClosure,
            failureAtReplayActionIndex = forward.divergedAtAction,
            failureReason = forward.failure ?: shapeFailure ?: closureFailure ?: forward.unverifiedReason,
        )
    }

    private fun buildBoundary(
        afterActionCount: Int,
        state: GameState,
        includeChosenInput: Boolean = false,
    ): PublicBoundary {
        val truncated = isTailBoundary(afterActionCount) &&
            tailClosure is EpisodeClosureV1.Interrupted
        val perspective = ObservationPerspective.resolve(
            state = state,
            playerIds = replayPlayerIds,
            fallbackPerspectivePlayerIndex = fallbackPerspectivePlayerIndex,
            truncated = truncated,
        ) ?: error("Replay boundary $afterActionCount has no public perspective")
        val agentToAct = if (state.gameOver) {
            null
        } else {
            state.pendingDecision?.playerId ?: state.priorityPlayerId
        }
        val legalActions = if (state.pendingDecision == null && agentToAct != null &&
            !state.gameOver && !truncated
        ) {
            legalActionEnumerator.enumerate(state, agentToAct, EnumerationMode.ACTIONS_ONLY)
                .filterNot { it.hasUnfillableTargetRequirement }
        } else {
            emptyList()
        }
        val result = observationBuilder.build(
            state = state,
            perspectivePlayerId = perspective,
            legalActions = legalActions,
            truncated = truncated,
        )
        require(result.diagnostics.isEmpty()) {
            "Replay boundary $afterActionCount produced unsupported public diagnostics: " +
                result.diagnostics
        }
        val observation = result.observation as? TrainingObservation
            ?: error("Verified replay requires a TrainingObservation at boundary $afterActionCount")
        val playerObservation = PlayerObservationV1.from(observation)
        val domain = CompleteLegalDomainV1.from(observation)
        val candidateDomainDigest = CandidateDomainDigestV1.from(domain)
        return PublicBoundary(
            frame = VerifiedReplayFrame(
                replayActionIndex = afterActionCount,
                perspectivePlayerId = perspective,
                observation = playerObservation,
                domain = domain,
                candidateDomainDigest = candidateDomainDigest,
            ),
            observation = observation,
            registry = result.registry,
            legalActions = legalActions,
            legalActionViews = result.registry.legalActions.mapNotNull { (actionId, action) ->
                observation.legalActions
                    .singleOrNull { view -> view.actionId == actionId }
                    ?.let { view -> action to view }
            },
            legalActionCandidates = if (includeChosenInput) {
                result.registry.legalActions.map { (actionId, action) ->
                    val viewIndex = observation.legalActions.indexOfFirst { it.actionId == actionId }
                    require(viewIndex >= 0) {
                        "Replay boundary $afterActionCount has no public view for action $actionId"
                    }
                    action to (domain.candidates.getOrNull(viewIndex) ?: throw IllegalArgumentException(
                        "Replay boundary $afterActionCount has no semantic candidate for action $actionId"
                    ))
                }
            } else {
                emptyList()
            },
            pendingDecisionId = state.pendingDecision?.id,
        )
    }

    private fun verifyRecordedAction(
        index: Int,
        state: GameState,
        boundary: PublicBoundary,
        action: GameAction,
        includeChosenInput: Boolean,
    ): ReplayChosenInputV1? {
        val pending = boundary.observation.pendingDecision
        if (pending != null) {
            val submitted = action as? SubmitDecision
                ?: throw IllegalArgumentException(
                    "Replay action $index is not a response to the pending decision",
                )
            require(submitted.playerId == pending.playerId) {
                "Replay action $index responds from ${submitted.playerId}, expected ${pending.playerId}"
            }
            val pendingId = checkNotNull(boundary.pendingDecisionId) {
                "Replay action $index has a pending public decision without a routing identity"
            }
            val rebound = submitted.response.withDecisionId(pendingId)
            val decision = checkNotNull(state.pendingDecision) {
                "Replay action $index has no authoritative pending decision"
            }
            // A4 proves the reconstructed Rules boundary and routes the response through the
            // existing validator.  DTO-level A3 semantic-response membership is deliberately an
            // A5 admission concern: keeping it here would either duplicate that validator or add
            // the forbidden game-server -> gym-trainer dependency.
            val validationFailure = DecisionValidators.validate(decision, rebound, state)
            require(validationFailure == null) {
                "Replay response at action $index is not a valid public decision choice: " +
                    validationFailure
            }
            if (!pending.requiresStructuredResponse) {
                val registered = boundary.registry.decisionResponses.map { it.second }
                require(registered.any { it == rebound }) {
                    "Replay response at action $index is outside the folded public decision domain"
                }
            }
            if (!includeChosenInput) return null
            return ReplayChosenInputV1(
                replayActionIndex = index,
                perspectivePlayerId = boundary.frame.perspectivePlayerId,
                chosenSemanticResponse = ChosenSemanticResponseV1.from(
                    domain = boundary.frame.domain,
                    response = submitted.response,
                ),
            )
        }

        require(action !is SubmitDecision) {
            "Replay action $index supplied a decision response without a pending decision"
        }
        val candidate = boundary.legalActions.firstOrNull {
            GameActionCandidateMatcher.matches(it.action, action)
        } ?: throw IllegalArgumentException(
            "Replay action $index is not a current public legal candidate",
        )

        ActionPayloadRequirements.requireTargetDomainSupported(candidate)
        AttackDeclarationDomainSubmission.requireSupported(candidate)
        BlockerDeclarationDomainSubmission.requireSupported(candidate)
        ManaColorDomainSubmission.requireSupported(candidate)
        ActionPayloadRequirements.requireTargetPayloadPartition(candidate, action)
        AttackDeclarationDomainSubmission.requireWithinRegisteredDomain(candidate, action)
        BlockerDeclarationDomainSubmission.requireWithinRegisteredDomain(candidate, action)
        ManaColorDomainSubmission.requireWithinRegisteredDomain(candidate, action)
        ActionPaymentPlanValidator.require(
            state = state,
            legalAction = candidate,
            submitted = action,
            observationBuilder = observationBuilder,
            publicView = boundary.publicViewFor(candidate),
        )
        if (!includeChosenInput) return null
        return ReplayChosenInputV1(
            replayActionIndex = index,
            perspectivePlayerId = boundary.frame.perspectivePlayerId,
            chosenSemanticAction = ChosenSemanticActionV1.fromRecordedAction(
                domain = boundary.frame.domain,
                candidate = boundary.semanticCandidateFor(candidate),
                action = action,
            ),
        )
    }

    private fun checkpointShapeFailure(): String? {
        val counts = replay.checkpoints.map { it.afterActionCount }
        val outOfRange = replay.checkpoints.firstOrNull {
            it.afterActionCount !in 0..replay.actions.size
        }
        val duplicate = counts.groupingBy { it }.eachCount().entries
            .firstOrNull { it.value > 1 }
        return when {
            outOfRange != null ->
                "v5 replay contains a checkpoint outside the applied action stream at " +
                    outOfRange.afterActionCount
            duplicate != null ->
                "v5 replay contains duplicate checkpoints at ${duplicate.key}"
            replay.actions.isEmpty() && counts.count { it == 0 } != 1 ->
                "v5 zero-action replay requires its tail checkpoint at action count 0"
            counts.count { it == replay.actions.size } != 1 ->
                "v5 replay requires exactly one tail checkpoint at ${replay.actions.size}"
            counts.filterNot { it == 0 }.sorted() != expectedCheckpointCounts() ->
                "v5 replay checkpoint coverage is incomplete; expected " +
                    expectedCheckpointCounts() + ", found ${counts.sorted()}"
            else -> null
        }
    }

    private fun expectedCheckpointCounts(): List<Int> = buildList {
        var next = ReplayRecordingPolicy.CHECKPOINT_EVERY_ACTIONS
        while (next < replay.actions.size) {
            add(next)
            next += ReplayRecordingPolicy.CHECKPOINT_EVERY_ACTIONS
        }
        if (replay.actions.isNotEmpty()) add(replay.actions.size)
    }

    private fun replayInputShapeFailure(): String? {
        val invalidYield = replay.yields.firstOrNull {
            it.afterActionCount !in 0..replay.actions.size
        }
        if (invalidYield != null) {
            return "v5 replay contains a yield outside the applied action stream at " +
                invalidYield.afterActionCount
        }
        val malformedYield = replay.yields.firstOrNull { yield ->
            when (yield.op) {
                ReplayYieldOp.SET -> yield.identity == null || yield.kind == null
                ReplayYieldOp.CLEAR_ABILITY -> yield.identity == null || yield.kind != null
                ReplayYieldOp.CLEAR_ALL -> yield.identity != null || yield.kind != null
            }
        }
        val unknownPlayerYield = replay.yields.firstOrNull {
            EntityId(it.playerId) !in replayPlayerIds
        }
        return (malformedYield ?: unknownPlayerYield)?.let {
            "v5 replay contains an incomplete yield mutation at action count " +
                it.afterActionCount
        }
    }

    private fun isTailBoundary(afterActionCount: Int): Boolean =
        afterActionCount == replay.actions.size

    private fun verifiedTailClosure(): EpisodeClosureV1 = tailClosure

    private fun tailClosureFailure(boundary: PublicBoundary): String? {
        val closure = verifiedTailClosure()
        if (closure.stepCount != replay.actions.size) {
            return "replay closure step count ${closure.stepCount} does not match " +
                "the action-stream tail ${replay.actions.size}"
        }
        return when (closure) {
            is EpisodeClosureV1.GameTerminal -> when {
                !boundary.observation.terminated ->
                    "terminal replay closure disagrees with the reconstructed tail"
                boundary.observation.truncated ->
                    "terminal replay closure cannot also be truncated"
                closure.winnerId != boundary.observation.winnerId ->
                    "terminal replay closure winner disagrees with the reconstructed tail"
                else -> null
            }

            is EpisodeClosureV1.Interrupted -> when {
                boundary.observation.terminated ->
                    "interrupted replay closure disagrees with a terminal reconstructed tail"
                !boundary.observation.truncated ->
                    "interrupted replay closure was not applied to the reconstructed tail"
                else -> null
            }

            is EpisodeClosureV1.Failed ->
                "failed replay closure cannot produce trusted public frames"
        }
    }

    private fun failure(
        fidelity: VerifiedReplayFidelity,
        reason: String,
        failureAt: Int?,
    ): VerifiedReplayVerification = VerifiedReplayVerification(
        replayVersion = replay.version,
        replayActionCount = replay.actions.size,
        verifiedActionCount = 0,
        fidelity = fidelity,
        frames = emptyList(),
        initialCheckpointVerified = false,
        intermediateCheckpointsVerified = false,
        tailCheckpointVerified = false,
        closure = tailClosure,
        failureAtReplayActionIndex = failureAt,
        failureReason = reason,
    )

    private data class PublicBoundary(
        val frame: VerifiedReplayFrame,
        val observation: TrainingObservation,
        val registry: com.wingedsheep.gym.contract.ActionRegistry,
        val legalActions: List<LegalAction>,
        val legalActionViews: List<Pair<LegalAction, com.wingedsheep.gym.contract.LegalActionView>>,
        val legalActionCandidates: List<Pair<LegalAction, JsonObject>>,
        val pendingDecisionId: String?,
    ) {
        fun publicViewFor(action: LegalAction): com.wingedsheep.gym.contract.LegalActionView? =
            legalActionViews.firstOrNull { (candidate, _) -> candidate == action }?.second

        fun semanticCandidateFor(action: LegalAction): JsonObject =
            legalActionCandidates.firstOrNull { (candidate, _) -> candidate == action }?.second
                ?: throw IllegalArgumentException("Replay action has no semantic public candidate")
    }

    private fun ReplayFidelity.toVerified(): VerifiedReplayFidelity = when (this) {
        ReplayFidelity.EXACT -> VerifiedReplayFidelity.EXACT
        ReplayFidelity.UNVERIFIED -> VerifiedReplayFidelity.UNVERIFIED
        ReplayFidelity.DIVERGED -> VerifiedReplayFidelity.DIVERGED
    }
}
