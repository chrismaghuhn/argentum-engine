package com.wingedsheep.engine.replacement

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.ZoneEntryOptions
import com.wingedsheep.engine.handlers.effects.ZoneChangeRedirectResult
import com.wingedsheep.engine.handlers.effects.ZoneMovementUtils
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CommanderComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.scripting.*
import com.wingedsheep.sdk.scripting.effects.MoveToZoneEffect
import com.wingedsheep.sdk.scripting.references.Player
import kotlinx.serialization.Serializable

/**
 * Describes a game event that *would* happen, before it occurs.
 *
 * These are constructed by effect executors before performing their action,
 * then passed to [ReplacementEffectProcessor] which checks all active
 * replacement effects against this event. The processor returns an outcome
 * that either modifies the event, replaces it with a different effect, or
 * consumes it entirely.
 *
 * This is deliberately distinct from [com.wingedsheep.engine.core.GameEvent]
 * (which records what *did* happen) — pending events describe hypothetical
 * future events that may never occur if replacement effects consume them.
 *
 * Each domain (draw, damage, life, token creation, zone change, etc.)
 * defines its own subtype and implements the polymorphic methods that
 * the domain-agnostic [ReplacementEffectProcessor] calls:
 * - [matches] — check if an [EventPattern] describes this event
 * - [applyReplacement] — apply a [ReplacementEffect] to produce an outcome
 * - [createOptionalPrompt] — build a yes/no prompt + continuation for
 *   optional replacement effects (most domains return null = mandatory-only)
 */
@Serializable
sealed interface PendingGameEvent {

    /**
     * The player most affected by this event — used to determine who chooses
     * between multiple competing replacement effects (CR 616.1).
     */
    val affectedPlayerId: EntityId

    /**
     * The player who chooses the order of competing CR 616 replacement effects.
     * Most events use [affectedPlayerId]. Zone changes override this because an
     * object can be controlled by a player other than its owner, while the
     * Commander 903.9b choice still belongs to [ZoneChangePending.ownerId].
     */
    fun replacementOrderingPlayerId(state: GameState): EntityId = affectedPlayerId

    /**
     * Check whether the given [pattern] describes this event.
     *
     * @param pattern The [EventPattern] from a replacement effect's `appliesTo`
     * @param sourceControllerId The controller of the permanent granting the replacement
     * @param state Current game state (for condition evaluation)
     * @param context Optional execution context (for condition evaluation)
     * @return true if this event matches the pattern
     */
    fun matches(
        pattern: EventPattern,
        sourceControllerId: EntityId,
        state: GameState,
        context: EffectContext?
    ): Boolean

    /**
     * Match a complete replacement candidate. Most events only need the
     * pattern match above; zone-change replacements additionally carry a
     * cause qualifier that belongs to the replacement itself.
     */
    fun matchesReplacement(
        effect: ReplacementEffect,
        sourceControllerId: EntityId,
        state: GameState,
        context: EffectContext?
    ): Boolean = matches(effect.appliesTo, sourceControllerId, state, context)

    /**
     * Apply a [ReplacementEffect] to this event and produce a [ReplacementOutcome].
     *
     * @param effect The replacement effect to apply
     * @param state Current game state
     * @return The outcome (Modified, Replaced, or Consumed)
     */
    fun applyReplacement(effect: ReplacementEffect, state: GameState): ReplacementOutcome

    /**
     * Apply a gathered replacement while retaining its source identity. Most
     * event domains only need the effect value; zone changes also use the
     * source to preserve link/additional-effect metadata.
     */
    fun applyReplacement(gathered: GatheredReplacement, state: GameState): ReplacementOutcome =
        applyReplacement(gathered.effect, state)

    /**
     * Whether an optional replacement should be presented as a decision for
     * this event. Commander 903.9b is optional under normal play, but the
     * headless `alwaysDivertToCommand` preference supplies an automatic YES
     * while remaining inside CR 616 ordering.
     */
    fun isOptionalReplacement(gathered: GatheredReplacement, state: GameState): Boolean =
        gathered.effect.optional

    /**
     * CR 903.9b is explicitly exempt from CR 614.5. The event may therefore
     * gather that rule candidate again after another replacement changes the
     * event. A plain unchanged event is still protected from an immediate
     * re-prompt by the processor's temporary decline set.
     */
    fun canApplyReplacementMoreThanOnce(effect: ReplacementEffect): Boolean = false

    /**
     * Build a yes/no prompt and continuation for an optional replacement effect.
     *
     * Most event domains return null (no optional replacement support), causing the
     * processor to treat the effect as mandatory via [applyReplacement].
     *
     * @param decisionId Unique ID for the decision
     * @param gathered The matched replacement effect
     * @param state Current game state
     * @param context Execution context
     * @return An [OptionalPromptResult] with the decision and continuation, or null
     */
    fun createOptionalPrompt(
        decisionId: String,
        gathered: GatheredReplacement,
        state: GameState,
        context: EffectContext?,
        alreadyApplied: Set<ReplacementEffectIdentity> = emptySet(),
    ): OptionalPromptResult? = null

    /**
     * Return a continuation frame for any remaining work after a replacement
     * effect has been applied to this event, or null if none is needed.
     *
     * For [DrawPending] with remaining draws, this returns a
     * [DrawReplacementRemainingDrawsContinuation] so the draw loop can
     * continue after an optional or competing replacement resolves
     * (CR 614.11a — complete the replacement, then resume the sequence).
     * Most event domains return null (no remainder concept).
     */
    fun remainderContinuation(state: GameState): ContinuationFrame? = null

    /**
     * Return a continuation frame that **performs this event** once every
     * replacement has been applied to it, or null if the caller performs it
     * itself.
     *
     * Only reached on the paused path: when applying a replacement needed a
     * player decision, the call site that would have performed the event has
     * already returned, so the (modified) event has to be carried forward on
     * the continuation stack instead. A [ReplacementOutcome.Modified] leaves
     * the event still to happen — unlike `Replaced`/`Consumed`, where the
     * replacement *is* what happens — so without this the modified event is
     * silently dropped.
     *
     * Called on the **modified** event, so implementations read their own
     * post-replacement fields.
     */
    fun performContinuation(state: GameState): ContinuationFrame? = null

    /** Work required after a replacement-resolved zone change reaches the physical atom. */
    @Serializable
    sealed interface ZoneChangeCompletion

    /** A physical zone move can resume with no additional effect-level work. */
    @Serializable
    data object PlainZoneChangeCompletion : ZoneChangeCompletion

    /**
     * Completion mode used by MoveToZoneEffectExecutor. The original effect
     * and context are retained so post-transition work is run after a paused
     * Commander decision resolves.
     */
    @Serializable
    data class MoveEffectZoneChangeCompletion(
        val effect: MoveToZoneEffect,
        val context: EffectContext
    ) : ZoneChangeCompletion

    /** Resume an activated ability after a cost permanent's hand move completes. */
    @Serializable
    data class ActivateAbilityZoneChangeCompletion(
        val action: ActivateAbility,
        val resolvedEntityId: EntityId
    ) : ZoneChangeCompletion

    /** Resume a spell cast after a Sneak/web-slinging/additional-cost hand move completes. */
    @Serializable
    data class CastSpellZoneChangeCompletion(
        val action: CastSpell,
        val resolvedEntityId: EntityId
    ) : ZoneChangeCompletion

    /** Mark a publicly ordered card after the physical library transition completes. */
    @Serializable
    data object LibraryRevealZoneChangeCompletion : ZoneChangeCompletion

    /** Emit the legacy spell-return event after a stack spell enters its library. */
    @Serializable
    data object StackSpellToLibraryZoneChangeCompletion : ZoneChangeCompletion

    /**
     * Finish a stack spell's fizzle/counter disposition after its zone transition resolves.
     * The disposition event is deliberately emitted only after the physical transition, so a
     * paused 903.9b choice cannot report a spell as countered while it is still on the stack.
     */
    @Serializable
    data class StackSpellDispositionZoneChangeCompletion(
        val fizzled: Boolean,
        val cardName: String,
        val reason: String? = null,
    ) : ZoneChangeCompletion

    /**
     * Preserve an already-pending spell-resolution decision while a stack-to-library move asks
     * the commander owner about 903.9b. The original decision frame remains below the replacement
     * frames; once the physical move finishes, the same decision is exposed again.
     */
    @Serializable
    data class ResumePendingDecisionZoneChangeCompletion(
        val pendingDecision: PendingDecision,
    ) : ZoneChangeCompletion

    /**
     * Resume a generic [PayCost] payment after one or more selected permanents have crossed the
     * 903.9b hand boundary. The selected Commander cards are already physically moved by the
     * time this completion runs; the payment atom must therefore receive only the remaining
     * selections so it cannot move them a second time.
     */
    @Serializable
    data class CostPaymentZoneChangeCompletion(
        val continuation: CostPaymentContinuation,
        val nonCommanderSelectedCards: List<EntityId>,
        val remainingCommanderIds: List<EntityId>,
    ) : ZoneChangeCompletion

    /** Resume the remaining bookkeeping of a MoveCollection after one card's 903.9b choice. */
    @Serializable
    data class MoveCollectionZoneChangeCompletion(
        val context: EffectContext,
        val cards: List<EntityId>,
        val destination: com.wingedsheep.sdk.scripting.effects.CardDestination.ToZone,
        val destPlayerId: EntityId,
        val revealed: Boolean = false,
        val moveType: com.wingedsheep.sdk.scripting.effects.MoveType =
            com.wingedsheep.sdk.scripting.effects.MoveType.Default,
        val faceDown: com.wingedsheep.sdk.scripting.effects.FaceDownMode? = null,
        val noRegenerate: Boolean = false,
        val storeMovedAs: String? = null,
        val underOwnersControl: Boolean = false,
        val revealToSelf: Boolean = true,
        /** Next card in the already ordered physical movement plan. */
        val nextCardIndex: Int = 0,
        /** Cards whose physical transitions completed before this pending move. */
        val completedCardIds: List<EntityId> = emptyList(),
        /** Libraries that already received a card before this pending move. */
        val completedLibraryOwnerIds: List<EntityId> = emptyList(),
    ) : ZoneChangeCompletion

    /**
     * A serializable zone-change event that is still hypothetical. Physical
     * mutation is deferred until the replacement processor has finished.
     */
    @Serializable
    data class ZoneChangePending(
        val entityId: EntityId,
        val ownerId: EntityId,
        val fromZoneKey: ZoneKey,
        val destinationZone: Zone,
        val entryOptions: ZoneEntryOptions = ZoneEntryOptions(),
        val completion: ZoneChangeCompletion = PlainZoneChangeCompletion,
        val redirectResult: com.wingedsheep.engine.handlers.effects.ZoneChangeRedirectResult? = null
    ) : PendingGameEvent {
        override val affectedPlayerId: EntityId get() = ownerId

        override fun replacementOrderingPlayerId(state: GameState): EntityId =
            state.projectedState.getController(entityId)
                ?: state.getEntity(entityId)?.get<ControllerComponent>()?.playerId
                ?: ownerId

        private val fromZone: Zone get() = fromZoneKey.zoneType

        /** CR 903.9b candidate gate: destination is hand/library, source is unrestricted. */
        fun commanderRuleApplies(state: GameState): Boolean {
            val container = state.getEntity(entityId) ?: return false
            return state.format.usesCommanders &&
                container.has<CommanderComponent>() &&
                destinationZone in setOf(Zone.HAND, Zone.LIBRARY)
        }

        override fun matches(
            pattern: EventPattern,
            sourceControllerId: EntityId,
            state: GameState,
            context: EffectContext?
        ): Boolean {
            val zoneChange = pattern as? EventPattern.ZoneChangeEvent ?: return false
            return ZoneMovementUtils.matchesZoneChangePattern(
                state = state,
                entityId = entityId,
                fromZone = fromZone,
                toZone = destinationZone,
                pattern = zoneChange,
                sourceControllerId = sourceControllerId,
            )
        }

        override fun matchesReplacement(
            effect: ReplacementEffect,
            sourceControllerId: EntityId,
            state: GameState,
            context: EffectContext?
        ): Boolean {
            val zoneChange = effect.appliesTo as? EventPattern.ZoneChangeEvent ?: return false
            val requiredCause = when (effect) {
                is RedirectZoneChange -> effect.requiredCause
                else -> ZoneChangeCause.Any
            }
            return ZoneMovementUtils.matchesZoneChangePattern(
                state = state,
                entityId = entityId,
                fromZone = fromZone,
                toZone = destinationZone,
                pattern = zoneChange,
                sourceControllerId = sourceControllerId,
                requiredCause = requiredCause,
            )
        }

        override fun applyReplacement(effect: ReplacementEffect, state: GameState): ReplacementOutcome =
            applyReplacementInternal(
                effect,
                state,
                sourceEntityId = null,
                sourceControllerId = null,
            )

        override fun applyReplacement(
            gathered: GatheredReplacement,
            state: GameState
        ): ReplacementOutcome = applyReplacementInternal(
            gathered.effect,
            state,
            sourceEntityId = gathered.sourceEntityId(state),
            sourceControllerId = gathered.sourceControllerId,
        )

        private fun applyReplacementInternal(
            effect: ReplacementEffect,
            state: GameState,
            sourceEntityId: EntityId?,
            sourceControllerId: EntityId?,
        ): ReplacementOutcome {
            return when (effect) {
                is CommanderZoneReplacement -> {
                    if (!commanderRuleApplies(state)) {
                        error("Commander 903.9b replacement no longer applies to $entityId")
                    }
                    ReplacementOutcome.Modified(
                        copy(
                            destinationZone = Zone.COMMAND,
                            // Preserve the accepted destination through the physical atom even
                            // when no ordinary zone replacement had supplied a redirect result.
                            // Otherwise intrinsic leave-battlefield redirects would be re-read
                            // after the pending 903.9b choice and could turn YES into EXILE.
                            redirectResult = ZoneChangeRedirectResult(Zone.COMMAND)
                        )
                    )
                }

                is RedirectZoneChange -> {
                    val redirect = com.wingedsheep.engine.handlers.effects.ZoneChangeRedirectResult(
                        destinationZone = effect.newDestination,
                        linkSourceId = if (effect.linkToSource && effect.newDestination == Zone.EXILE) {
                            sourceEntityId
                        } else null,
                        shuffleIntoLibrary = effect.shuffleIntoLibrary,
                        reveal = effect.reveal,
                    )
                    ReplacementOutcome.Modified(
                        copy(
                            destinationZone = effect.newDestination,
                            entryOptions = if (effect.shuffleIntoLibrary && effect.newDestination == Zone.LIBRARY) {
                                entryOptions.copy(
                                    libraryPlacement = com.wingedsheep.engine.handlers.effects.LibraryPlacement.Shuffled
                                )
                            } else entryOptions,
                            redirectResult = redirect,
                        )
                    )
                }

                is RedirectZoneChangeWithEffect -> {
                    val redirect = com.wingedsheep.engine.handlers.effects.ZoneChangeRedirectResult(
                        destinationZone = effect.newDestination,
                        additionalEffect = effect.additionalEffect,
                        effectControllerId = sourceControllerId,
                        linkSourceId = if (effect.linkToSource && effect.newDestination == Zone.EXILE) {
                            sourceEntityId
                        } else null,
                    )
                    ReplacementOutcome.Modified(
                        copy(destinationZone = effect.newDestination, redirectResult = redirect)
                    )
                }

                else -> error(
                    "Unsupported replacement effect type '${effect::class.simpleName}' for ZoneChangePending"
                )
            }
        }

        override fun isOptionalReplacement(gathered: GatheredReplacement, state: GameState): Boolean =
            gathered.effect.optional && !(
                gathered.effect is CommanderZoneReplacement && state.format.alwaysDivertToCommand
                )

        override fun canApplyReplacementMoreThanOnce(effect: ReplacementEffect): Boolean =
            effect is CommanderZoneReplacement

        override fun createOptionalPrompt(
            decisionId: String,
            gathered: GatheredReplacement,
            state: GameState,
            context: EffectContext?,
            alreadyApplied: Set<ReplacementEffectIdentity>,
        ): OptionalPromptResult? {
            if (gathered.effect !is CommanderZoneReplacement) return null

            val cardName = state.getEntity(entityId)
                ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                ?.name
                ?: "Commander"
            val decision = YesNoDecision(
                id = decisionId,
                playerId = ownerId,
                prompt = "Put $cardName into the command zone instead of putting it into your ${destinationZone.displayName}?",
                context = DecisionContext(
                    sourceId = entityId,
                    sourceName = cardName,
                    phase = DecisionPhase.RESOLUTION,
                )
            )
            return OptionalPromptResult(
                decision = decision,
                continuation = OptionalReplacementContinuation(
                    decisionId = decisionId,
                    pendingEvent = this,
                    gathered = gathered,
                    alreadyApplied = alreadyApplied,
                    context = context,
                )
            )
        }

        override fun performContinuation(state: GameState): ContinuationFrame =
            ZoneChangeContinuation(
                decisionId = "pending",
                pendingEvent = this,
            )
    }

    /**
     * Draw event: a player is about to draw cards from their library.
     */
    @Serializable
    data class DrawPending(
        val playerId: EntityId,
        val count: Int,
        val remainingDraws: Int = 0,
        val isDrawStep: Boolean = false,
        val drawnCardsSoFar: List<EntityId> = emptyList()
    ) : PendingGameEvent {
        override val affectedPlayerId: EntityId get() = playerId

        /** Total draws remaining including this one (derived from remainingDraws + 1). */
        val drawsLeft: Int get() = remainingDraws + 1

        override fun matches(
            pattern: EventPattern,
            sourceControllerId: EntityId,
            state: GameState,
            context: EffectContext?
        ): Boolean {
            val drawEvent = pattern as? EventPattern.DrawEvent ?: return false
            if (drawEvent.exceptFirstInDrawStep && drawnCardsSoFar.isEmpty()) return false
            return matchesPlayerFilter(drawEvent.player, playerId, sourceControllerId, state)
        }

        /**
         * [ModifyDrawAmount] is deliberately absent: it only ever applies to the
         * announcement (CR 121.2a), and its `appliesTo` is typed as
         * [EventPattern.DrawCardsEvent] so it can never match this per-card event.
         * Adjusting a draw *count* here would not terminate — the draw loop would
         * re-check an unchanged game state and re-match the same effect forever.
         */
        override fun applyReplacement(effect: ReplacementEffect, state: GameState): ReplacementOutcome {
            return when (effect) {
                is PreventDraw -> ReplacementOutcome.Consumed
                is ReplaceDrawWithEffect -> ReplacementOutcome.Replaced(effect.replacementEffect)
                else -> error("Unsupported replacement effect type '${effect::class.simpleName}' for ${this::class.simpleName}")
            }
        }

        override fun remainderContinuation(state: GameState): ContinuationFrame? {
            if (remainingDraws > 0) {
                return DrawReplacementRemainingDrawsContinuation(
                    drawingPlayerId = playerId,
                    remainingDraws = remainingDraws,
                    isDrawStep = isDrawStep,
                    // Part of an instruction that was announced before the per-card
                    // loop started — re-announcing would apply ModifyDrawAmount twice.
                    announcementApplied = true
                )
            }
            return null
        }

        override fun createOptionalPrompt(
            decisionId: String,
            gathered: GatheredReplacement,
            state: GameState,
            context: EffectContext?,
            alreadyApplied: Set<ReplacementEffectIdentity>,
        ): OptionalPromptResult? {
            val replaceEffect = gathered.effect as? ReplaceDrawWithEffect ?: return null
            val sourceEntityId = gathered.sourceEntityId(state)
            val sourceEntity = sourceEntityId?.let { state.getEntity(it) }
            val card = sourceEntity?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
            val cardName = card?.name ?: "Unknown"
            val linkedExile = sourceEntity
                ?.get<com.wingedsheep.engine.state.components.battlefield.LinkedExileComponent>()
            val pileCount = linkedExile?.exiledIds?.size

            val prompt = buildString {
                // The effect's own text, not gathered.description — the latter is already
                // prefixed with the card name, which this line supplies.
                append("Use $cardName? ${replaceEffect.description}")
                if (pileCount != null) {
                    append(" ($pileCount cards remaining)")
                }
            }

            val decision = YesNoDecision(
                id = decisionId,
                playerId = affectedPlayerId,
                prompt = prompt,
                context = DecisionContext(
                    sourceId = sourceEntityId,
                    sourceName = cardName,
                    phase = DecisionPhase.RESOLUTION
                )
            )

            val continuation = StaticDrawReplacementContinuation(
                decisionId = decisionId,
                drawingPlayerId = playerId,
                sourceId = sourceEntityId ?: EntityId(""),
                sourceName = cardName,
                replacementEffect = replaceEffect.replacementEffect,
                drawCount = drawsLeft,
                isDrawStep = isDrawStep,
                drawnCardsSoFar = drawnCardsSoFar,
                declinedIdentity = gathered.identity
            )

            return OptionalPromptResult(
                decision = decision,
                continuation = continuation
            )
        }
    }

    /**
     * Draw announcement event: a draw instruction says a player will draw N cards.
     *
     * Created **once** per draw instruction (spell, ability, or draw-step),
     * **before** the per-card [DrawLoop] fires. This allows `ModifyDrawAmount`
     * replacement effects using [EventPattern.DrawCardsEvent] (e.g. "if you
     * would draw two or more cards") to adjust the total before any individual
     * card is drawn (CR 121.2a).
     *
     * This event **only** matches [EventPattern.DrawCardsEvent].
     */
    @Serializable
    data class DrawAmountPending(
        val playerId: EntityId,
        val totalCount: Int,
        val isDrawStep: Boolean = false
    ) : PendingGameEvent {
        override val affectedPlayerId: EntityId get() = playerId

        override fun matches(
            pattern: EventPattern,
            sourceControllerId: EntityId,
            state: GameState,
            context: EffectContext?
        ): Boolean {
            return pattern is EventPattern.DrawCardsEvent &&
                totalCount >= pattern.amount &&
                matchesPlayerFilter(pattern.player, playerId, sourceControllerId, state)
        }

        override fun applyReplacement(effect: ReplacementEffect, state: GameState): ReplacementOutcome {
            return when (effect) {
                is ModifyDrawAmount -> ReplacementOutcome.Modified(
                    copy(
                        totalCount = (totalCount * effect.multiplier + effect.modifier).coerceAtLeast(0)
                    )
                )
                is PreventDraw -> ReplacementOutcome.Consumed
                is ReplaceDrawWithEffect -> ReplacementOutcome.Replaced(effect.replacementEffect)
                else -> error("Unsupported replacement effect type '${effect::class.simpleName}' for ${this::class.simpleName}")
            }
        }

        /**
         * The announcement itself performs no draws — the per-card loop does. When a
         * competing-replacement choice paused the announcement, the executor that would
         * have run that loop has already returned, so the modified instruction is carried
         * forward as a draw of [totalCount] with the announcement marked as done.
         */
        override fun performContinuation(state: GameState): ContinuationFrame? {
            if (totalCount <= 0) return null
            return DrawReplacementRemainingDrawsContinuation(
                drawingPlayerId = playerId,
                remainingDraws = totalCount,
                isDrawStep = isDrawStep,
                announcementApplied = true
            )
        }
    }
}

/**
 * Result of [PendingGameEvent.createOptionalPrompt].
 *
 * @property decision The yes/no decision to present to the player
 * @property continuation The continuation frame to resume after the player answers
 */
data class OptionalPromptResult(
    val decision: PendingDecision,
    val continuation: ContinuationFrame
)

private fun matchesPlayerFilter(
    player: Player,
    affectedPlayerId: EntityId,
    sourceControllerId: EntityId,
    state: GameState
): Boolean {
    return when (player) {
        Player.Each, Player.Any -> true
        Player.You -> affectedPlayerId == sourceControllerId
        Player.EachOpponent, Player.AnOpponent -> affectedPlayerId in state.getOpponents(sourceControllerId)
        else -> error("Unsupported player filter '$player' in matchesPlayerFilter")
    }
}
