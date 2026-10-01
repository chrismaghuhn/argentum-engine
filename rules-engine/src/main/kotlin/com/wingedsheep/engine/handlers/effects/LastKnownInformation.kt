package com.wingedsheep.engine.handlers.effects

import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.state.components.stack.EntitySnapshot
import com.wingedsheep.engine.state.components.stack.snapshotFor
import com.wingedsheep.engine.state.components.stack.stampedFor
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.stack.isCapturedBattlefieldObjectLive
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.targets.EffectTarget

/**
 * Whether a value read of an [EffectTarget.SingleEntity] falls back to last-known information
 * (CR 113.7a / 603.10 / 608.2h) when its referenced permanent is no longer on the battlefield, or
 * only ever reads the live board.
 *
 * Declared as one exhaustive mapping ([lkiPolicyFor]) so that adding a new
 * [EffectTarget.SingleEntity] is a compile error until its last-known behavior is classified — the
 * fallback can no longer be silently forgotten, nor silently applied where it must not be.
 * Filtered enumeration (Gather / ForEach) is deliberately not modeled here at all: it reads the
 * live battlefield, and a permanent that has left simply is not in the set.
 *
 * This governs value reads only. An *action* aimed at an object that has changed zones does
 * nothing (CR 400.7) — [TargetResolutionUtils.resolveTarget] gates that, not this policy.
 */
enum class LkiPolicy {
    /** Always read the live board; a departed permanent reads as absent (targets, iteration, Amass). */
    LIVE_ONLY,

    /** Read live while on the battlefield; once it has left, read the captured [EntitySnapshot]. */
    LIVE_THEN_LKI,
}

fun lkiPolicyFor(reference: EffectTarget.SingleEntity): LkiPolicy = when (reference) {
    // A self-sacrificing/exiling source (or granter), the triggering permanent of a dies/leaves
    // trigger, the source and recipient of the damage that fired a trigger, the host of an
    // Aura/Equipment that detached, and cost-paid permanents (sacrificed / tapped / chosen) are all
    // routinely read after they have left the battlefield (CR 113.7a / 608.2h).
    EffectTarget.Self,
    EffectTarget.GrantingSource,
    EffectTarget.TriggeringEntity,
    EffectTarget.DamageSource,
    EffectTarget.DamageRecipient,
    EffectTarget.EnchantedCreature,
    EffectTarget.EquippedCreature,
    EffectTarget.EnchantedPermanent,
    is EffectTarget.SacrificedAsCost,
    is EffectTarget.TappedAsCost,
    is EffectTarget.PipelineTarget,
    -> LkiPolicy.LIVE_THEN_LKI

    // Targets are re-validated at resolution (a departed target fizzles, CR 608.2b); iteration and
    // Amass references only ever name a live permanent; Ring-bearer must be on the battlefield; a
    // chosen creature or an attachment host is read where it is now.
    EffectTarget.AffectedEntity,
    EffectTarget.IterationEntity,
    EffectTarget.AmassedArmy,
    EffectTarget.ChosenCreature,
    EffectTarget.AttachedToTriggeringPermanent,
    is EffectTarget.ContextTarget,
    is EffectTarget.BoundVariable,
    is EffectTarget.SpecificEntity,
    is EffectTarget.RingBearer,
    // A library card, an Imprint pile's card and a discarded card are *never* on the battlefield —
    // their characteristics are the printed ones. There is nothing to snapshot, and asking for one
    // would be wrong: the read must fall through to base characteristics, which is what LIVE_ONLY
    // does.
    is EffectTarget.LibraryTop,
    is EffectTarget.LinkedExiledCard,
    is EffectTarget.DiscardedAsCost,
    // The spell or ability that did the targeting is a stack object, never a permanent.
    EffectTarget.TargetingSource,
    -> LkiPolicy.LIVE_ONLY
}

/**
 * The captured [EntitySnapshot] backing a [LkiPolicy.LIVE_THEN_LKI] [reference] that resolved to
 * [entityId] after it left the battlefield, or null if none was captured (the read then falls
 * through to base characteristics). Triggering- and enchanted-creature last-known P/T are still
 * carried as scalars on [EffectContext] and resolved at their own read sites, so they — like every
 * reference with no captured snapshot — return null here.
 *
 * [state] is the identity witness for the references whose capture is stamped with a
 * battlefield-entry incarnation — the source's own snapshot and the damage source / recipient
 * snapshots: their snapshot is answered only while that incarnation is *not* the object on the
 * battlefield, so a live original keeps reading its projected characteristics, and a same-id
 * object that re-entered as a new object (CR 400.7) never borrows them either way. A damage-role
 * snapshot without a stamp proves nothing and is never answered.
 */
fun EffectContext.lkiSnapshotFor(
    reference: EffectTarget.SingleEntity,
    entityId: EntityId,
    state: GameState,
): EntitySnapshot? =
    when (reference) {
        is EffectTarget.SacrificedAsCost -> sacrificedPermanents.snapshotFor(entityId)
        is EffectTarget.TappedAsCost -> tappedEntitySnapshots.snapshotFor(entityId)
        is EffectTarget.PipelineTarget -> chosenEntitySnapshots.snapshotFor(entityId)
        EffectTarget.Self -> lastKnownSourceSnapshot
            ?.takeIf { it.entityId == entityId && !state.isCapturedBattlefieldObjectLive(entityId, it) }
        EffectTarget.DamageSource -> triggerContext?.damageSourceLastKnownSnapshot
            .stampedFor(entityId)
            ?.takeIf { !state.isCapturedBattlefieldObjectLive(entityId, it) }
        EffectTarget.DamageRecipient -> triggerContext?.damageRecipientLastKnownSnapshot
            .stampedFor(entityId)
            ?.takeIf { !state.isCapturedBattlefieldObjectLive(entityId, it) }
        else -> null
    }
