package com.wingedsheep.engine.state.components.stack

import com.wingedsheep.engine.state.components.identity.TextChanges
import com.wingedsheep.engine.state.components.identity.TextReplacementComponent
import com.wingedsheep.engine.mechanics.layers.ProjectedState
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.AttachmentsComponent
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.state.components.combat.AttackingComponent
import com.wingedsheep.engine.state.components.combat.BlockingComponent
import com.wingedsheep.engine.state.components.battlefield.DamageSourceLki
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.battlefield.BattlefieldEntryTimestampComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.Supertype
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.Serializable

/**
 * Read-only view of a permanent's characteristics. Implemented by both the *live* projected
 * board ([LiveEntityView], a thin [ProjectedState] adapter) and a *frozen* [EntitySnapshot].
 *
 * This is the single abstraction over "read this permanent's property" that lets last-known
 * information (CR 113.7a / 603.10 / 608.2h) resolve uniformly: a read site asks for an
 * [EntityView] for an entity — live if it is still on the battlefield, otherwise its captured
 * snapshot — and reads the same accessors regardless of whether the permanent is still in play.
 */
interface EntityView {
    val entityId: EntityId
    val power: Int?
    val toughness: Int?
    val controllerId: EntityId?

    /** Count of each counter kind the entity carries. */
    val counters: Map<CounterType, Int>
    /** Projected colors at capture time, represented by their stable enum names. */
    val colors: Set<String>
    val keywords: Set<String>
    val subtypes: Set<String>
    val supertypes: Set<String>
    val lostAllAbilities: Boolean

    val plusOnePlusOneCounters: Int get() = counters[CounterType.PLUS_ONE_PLUS_ONE] ?: 0
    val minusOneMinusOneCounters: Int get() = counters[CounterType.MINUS_ONE_MINUS_ONE] ?: 0
    val totalCounters: Int get() = counters.values.sum()
}

/**
 * Live, projection-backed [EntityView]. Reads flow straight through to [GameState.projectedState]
 * (and the entity's [CountersComponent] for counters, which projection does not surface), so the
 * values are always current. Used as the "still on the battlefield" arm of last-known resolution.
 */
class LiveEntityView(
    private val state: GameState,
    override val entityId: EntityId,
) : EntityView {
    private val projected: ProjectedState get() = state.projectedState
    override val power: Int? get() = projected.getPower(entityId)
    override val toughness: Int? get() = projected.getToughness(entityId)
    override val controllerId: EntityId? get() = projected.getController(entityId)
    override val counters: Map<CounterType, Int> get() = countersOf(state, entityId)
    override val colors: Set<String> get() = projected.getColors(entityId)
    override val keywords: Set<String> get() = projected.getKeywords(entityId)
    override val subtypes: Set<String> get() = projected.getSubtypes(entityId)
    override val supertypes: Set<String> get() = projected.getSupertypes(entityId)
    override val lostAllAbilities: Boolean get() = projected.hasLostAllAbilities(entityId)
}

/**
 * Frozen projected characteristics of a permanent captured at a specific moment — typically just
 * before it leaves the battlefield (CR 113.7a / 603.10 / 608.2h, "as it last existed on the
 * battlefield"). The single last-known-information value type for the whole engine. It backs:
 *
 * - **cost-time references** — sacrificed / tapped / chosen permanents and a self-sacrificing
 *   source (CR 113.7a), so "deals damage equal to its power" reads the pre-cost power; and
 * - **death / leaves-the-battlefield triggers** — carried as a single value on
 *   [com.wingedsheep.engine.core.ZoneChangeEvent.lastKnown] and threaded into trigger resolution,
 *   replacing what used to be ~16 parallel `lastKnown*` scalar fields.
 *
 * The first six parameters preserve the order of the former `PermanentSnapshot` so positional
 * construction at existing call sites is unaffected; the remaining fields (defaulted) carry the
 * death/leave last-known information that previously lived as loose scalars on the event.
 */
@Serializable
data class EntitySnapshot(
    override val entityId: EntityId,
    override val power: Int? = null,
    /** Printed base power captured with the projected power for relative-power predicates. */
    val basePower: Int? = null,
    override val toughness: Int? = null,
    override val subtypes: Set<String> = emptySet(),
    /** Projected supertypes at capture time (e.g. "LEGENDARY", "BASIC", "SNOW", "WORLD"). */
    override val supertypes: Set<String> = emptySet(),
    /**
     * Controller frozen at capture time, NOT at the eventual zone-leave. If control shifts after the
     * snapshot is taken (e.g. Threaten resolves while the ability is on the stack) and the permanent
     * then leaves, this reports the older controller — acceptable for current callers; revisit if a
     * card needs control-at-zone-leave fidelity.
     */
    override val controllerId: EntityId? = null,
    override val counters: Map<CounterType, Int> = emptyMap(),
    override val colors: Set<String> = emptySet(),
    override val keywords: Set<String> = emptySet(),
    override val lostAllAbilities: Boolean = false,
    // --- battlefield-exit-only fields (no meaning for a live permanent) ---
    /** Projected type line at capture, so leaves-battlefield triggers see continuous-effect-granted types. */
    val typeLine: TypeLine? = null,
    /** Card definition id, so dies/leaves triggers resolve for tokens after 704.5d cleanup. */
    val cardDefinitionId: String? = null,
    /** Effective text at departure, before zone movement ends the object's text changes. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val textChanges: TextReplacementComponent? = null,
    /**
     * Identity of this battlefield visit, retained after the entity changes zones: the
     * battlefield-object incarnation captured with this snapshot (CR 400.7). Entity ids are
     * reused by this engine across zone changes, so a damage reference must compare this stamp
     * before reading a live object; a mismatched stamp is a different object, never the captured
     * one.
     */
    val battlefieldEntryTimestamp: Long? = null,
    /**
     * A zone-independent incarnation stamp for event-time references. Battlefield objects use the
     * entry stamp above; stack/spell objects use the engine timestamp at capture. A non-null value
     * is required before a damage source or recipient id can authorize identity-sensitive matching.
     */
    val objectIncarnationStamp: Long? = null,
    /**
     * The permanent's name at capture time. Frozen because a *cost* has to be describable after the
     * permanent it consumed is gone: the emerge sacrifice (CR 702.119a) is named on the stack card
     * and in the game log, and a sacrificed token leaves no entity to look the name up on once
     * 704.5d cleanup has run.
     */
    val name: String? = null,
    /** The original card name when this permanent entered as a copy (Clever Impersonator). */
    val copyOfOriginalName: String? = null,
    /** For auras/equipment: the entity this was attached to when it left (enchanted-creature dies triggers). */
    val attachedTo: EntityId? = null,
    /**
     * True if this permanent had at least one Equipment attached when it left the battlefield. The
     * live attachment links are torn down by the exit cleanup, so leaves/dies triggers asking "was
     * it modified/equipped?" must read last-known information (CR 608.2h). Backs the last-known leg
     * of [com.wingedsheep.sdk.scripting.predicates.StatePredicate.IsEquipped] and (together with
     * counters / [wasEnchanted]) [com.wingedsheep.sdk.scripting.predicates.StatePredicate.IsModified].
     */
    val wasEquipped: Boolean = false,
    /**
     * The Auras/Equipment that were attached to this permanent when it left the battlefield, in
     * attachment order. [wasEquipped]/[wasEnchanted] answer "was it attached to *anything* of this
     * kind"; this field answers "by *what*", which is what an ATTACHED-bound leaves/dies trigger
     * borne by a still-on-the-battlefield Equipment needs to find itself again.
     *
     * The live links are torn down by the exit cleanup (CR 704.5m/n), and when the permanent dies
     * to a state-based action the unattach runs in the *same* SBA pass as the death — so by the
     * time triggers are detected, the attachment index no longer connects the Equipment to its
     * dead host. Freezing the ids here is the last-known information (CR 608.2h) that lets
     * `AttachmentTriggerDetector` still fire "whenever equipped creature dies".
     */
    val attachmentIds: List<EntityId> = emptyList(),
    /**
     * True if this permanent had at least one Aura attached when it left the battlefield (CR 303.4).
     * Last-known counterpart to [com.wingedsheep.sdk.scripting.predicates.StatePredicate.IsEnchanted];
     * a leg of the last-known [com.wingedsheep.sdk.scripting.predicates.StatePredicate.IsModified].
     */
    val wasEnchanted: Boolean = false,
    /** Creatures blocking, or blocked by, this one when it left (CR 509; Abu Ja'far). */
    val blockingOrBlockedByIds: List<EntityId> = emptyList(),
    /**
     * True if this permanent was an attacking creature (CR 506.4) at the moment it left the
     * battlefield. Frozen here because the live `AttackingComponent` is torn down by the exit's
     * combat cleanup, so a dies/leaves trigger asking "was it attacking?" has to read last known
     * information (CR 608.2h) — Garna, Bloodfist of Keld ("draw a card if it was attacking").
     * Backs the last-known leg of
     * [com.wingedsheep.sdk.scripting.predicates.StatePredicate.IsAttacking].
     */
    val wasAttacking: Boolean = false,
    /**
     * True if this permanent was a blocking creature (CR 509.1g) at capture time — the blocking
     * half of [wasAttacking]. Frozen because damage that kills a blocker moves it (and tears down
     * its `BlockingComponent`) before triggers are detected, so "whenever equipped creature deals
     * damage to a blocking creature" (Kusari-Gama) can only recognise that blocker from last-known
     * information (CR 603.10). Backs the last-known leg of
     * [com.wingedsheep.sdk.scripting.predicates.StatePredicate.IsBlocking].
     */
    val wasBlocking: Boolean = false,
    /**
     * What this permanent was attacking when it left the battlefield — the player, planeswalker or
     * battle its `AttackingComponent` named. The id half of [wasAttacking], and CR 802.2a is why it
     * has to be frozen: when the creature "is no longer attacking", the defending player its
     * ability refers to is still "the player that creature was attacking before it was removed from
     * combat". Mindstab Thrull sacrifices itself and *then* makes the defending player discard, so
     * by that point the live component the defender is normally read off has been torn down.
     */
    val attackedDefenderId: EntityId? = null,
    /** True if the leaving entity was a token (CR 704.5d — suppress persist-style return triggers). */
    val wasToken: Boolean = false,
    /** Whether the object was tapped at capture time. */
    val wasTapped: Boolean = false,
    /**
     * The permanent that created this one ([CreatedByComponent]), frozen as it left. A token is
     * swept out of existence before a leaves-the-battlefield trigger gates (CR 704.5d), so
     * "when **the token** leaves the battlefield" (Dance of Many) can only tell its own token from
     * anyone else's by last-known information. See
     * [com.wingedsheep.engine.state.components.identity.CreatedByComponent].
     */
    val createdBy: EntityId? = null,
    /**
     * True if this permanent carried the suspected designation (CR 701.60a) at capture time.
     *
     * Frozen because the designation is a floating effect keyed on the entity, and a sacrificed
     * permanent's floating effects are torn down with it — so "if the sacrificed creature was
     * suspected" (Agency Coroner) has to read last-known information (CR 608.2h), exactly as
     * [supertypes] does for "was legendary".
     */
    val wasSuspected: Boolean = false,
    /** Per-player damage dealt to this entity this turn, keyed by source-controller (Grothama). */
    val damageDealtByPlayers: Map<EntityId, Int> = emptyMap(),
    /** Snapshots of the sources that dealt damage to this entity this turn (Shelob, Child of Ungoliant). */
    val damageSources: Set<DamageSourceLki> = emptySet(),
    /** The cast-time {X} carried by `CastChoicesComponent`, so dies/leaves triggers read `DynamicAmount.CastX`. */
    val castX: Int? = null,
    /**
     * True if this permanent was face down (CR 708) when it left the battlefield — or, for a
     * snapshot of a live permanent, at capture time.
     *
     * Frozen because a card put into a graveyard is always turned face up (CR 708.4), so by the
     * time a dies trigger is gated the `FaceDownComponent` is gone along with the battlefield
     * entity — "whenever a face-down creature you control dies" (Yarus, Roar of the Old Gods) can
     * only be answered from last-known information (CR 608.2h). Backs the last-known leg of
     * [com.wingedsheep.sdk.scripting.predicates.StatePredicate.IsFaceDown] / `IsFaceUp`, the same
     * way [wasSuspected] does for the suspected designation.
     */
    val wasFaceDown: Boolean = false,
    /** Copy-added rules text, frozen before the original identity is restored on departure. */
    val copyTriggeredAbilities: List<com.wingedsheep.sdk.scripting.TriggeredAbility> = emptyList(),
    /**
     * The "as long as …" self-granted triggered abilities ([com.wingedsheep.sdk.scripting.ConditionalStaticAbility]
     * around a `Scope.Self` [com.wingedsheep.sdk.scripting.GrantTriggeredAbility]) whose condition held
     * immediately before the permanent left. Leaves-the-battlefield abilities look back in time
     * (CR 603.10a), and by trigger time the permanent has no controller to evaluate the condition
     * against — Oculus Whelp's granted "when this creature dies" is read from here. See
     * [com.wingedsheep.engine.event.ConditionalSelfGrants].
     */
    val conditionalSelfGrantIds: List<com.wingedsheep.sdk.scripting.AbilityId> = emptyList(),
    /**
     * Triggered abilities the object had at capture time from outside its own card definition: an
     * effect's "<object> gains '<triggered ability>' until end of turn" (Cruel Deceiver, Commando
     * Raid). Frozen onto a damage event's source and recipient snapshots by
     * [com.wingedsheep.engine.handlers.effects.DamageUtils.captureDamageRoleSnapshot].
     *
     * A damage trigger is checked against the objects as they exist immediately after the damage
     * event (CR 603.10), and state-based actions only run when a player would next receive
     * priority (CR 704.3) — so a creature that dies to that same damage still had its granted
     * abilities when the trigger condition was met. By detection time it is gone and its entity id
     * may already name a newer object, whose grants must never stand in; the departed-object path
     * reads these instead. Not encoded while empty, so snapshots without grants serialize as before.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val grantedTriggeredAbilities: List<com.wingedsheep.sdk.scripting.TriggeredAbility> = emptyList(),
) : EntityView {
    companion object {
        /**
         * Capture the projection-derivable characteristics of [entityId] from [state], including
         * counters/keywords/lost-abilities. Caller must invoke this BEFORE any zone change so
         * projected values still resolve. The zone-transition path augments the result with the
         * battlefield-exit-only fields ([typeLine], [attachedTo], …) via `copy(...)`.
         */
        fun fromProjection(entityId: EntityId, state: GameState): EntitySnapshot {
            val projected = state.projectedState
            return EntitySnapshot(
                entityId = entityId,
                power = projected.getPower(entityId),
                basePower = state.getEntity(entityId)?.get<CardComponent>()?.baseStats?.basePower,
                toughness = projected.getToughness(entityId),
                subtypes = projected.getSubtypes(entityId),
                supertypes = projected.getSupertypes(entityId),
                controllerId = projected.getController(entityId),
                counters = countersOf(state, entityId),
                colors = projected.getColors(entityId),
                keywords = projected.getKeywords(entityId),
                lostAllAbilities = projected.hasLostAllAbilities(entityId),
                wasSuspected = projected.isSuspected(entityId),
                wasToken = state.getEntity(entityId)?.has<TokenComponent>() == true,
                wasFaceDown = projected.isFaceDown(entityId),
                wasTapped = state.getEntity(entityId)?.has<TappedComponent>() == true,
                wasAttacking = state.getEntity(entityId)?.has<AttackingComponent>() == true,
                battlefieldEntryTimestamp = state.getEntity(entityId)
                    ?.get<BattlefieldEntryTimestampComponent>()?.timestamp,
                name = projected.getName(entityId)
                    ?: if (projected.isFaceDown(entityId)) {
                        null
                    } else {
                        state.getEntity(entityId)?.get<CardComponent>()?.name
                    },
            )
        }
    }
}

/** The non-zero counters on [entityId]. */
private fun countersOf(state: GameState, entityId: EntityId): Map<CounterType, Int> =
    state.getEntity(entityId)?.get<CountersComponent>()?.counters?.filterValues { it > 0 } ?: emptyMap()

/**
 * Capture frozen [EntitySnapshot]s (projected P/T, subtypes, supertypes, controller) for a list of
 * permanents, in order. Caller must invoke this BEFORE any zone change so projected values still
 * resolve. Used for cost-time last-known information (sacrificed / tapped / chosen permanents).
 */
fun captureEntitySnapshots(
    ids: List<EntityId>,
    projected: ProjectedState,
): List<EntitySnapshot> = ids.map { id ->
    EntitySnapshot(
        entityId = id,
        power = projected.getPower(id),
        toughness = projected.getToughness(id),
        subtypes = projected.getSubtypes(id),
        supertypes = projected.getSupertypes(id),
        controllerId = projected.getController(id),
        colors = projected.getColors(id),
        wasFaceDown = projected.isFaceDown(id),
        wasSuspected = projected.isSuspected(id),
    )
}

/**
 * [captureEntitySnapshots] overload that also records the facts only [GameState] carries, not
 * [ProjectedState]: each permanent's **token-ness** ([EntitySnapshot.wasToken], via [TokenComponent])
 * and its **name** ([EntitySnapshot.name], projected with a [CardComponent] fallback for face-up
 * permanents). Use at a sacrifice site when a following sibling needs either — Exploit's
 * `ExploitedEvent.sacrificedWasToken` (read by Skull Skaab's "exploits a nontoken creature" clause)
 * for the first, naming what an alternative cost ate for the second. Caller must invoke this BEFORE
 * the zone change so projected values, the token component and the card component all still resolve.
 */
fun captureEntitySnapshots(
    ids: List<EntityId>,
    state: GameState,
): List<EntitySnapshot> = captureEntitySnapshots(ids, state.projectedState).map { snapshot ->
    val container = state.getEntity(snapshot.entityId)
    snapshot.copy(
        wasToken = container?.has<TokenComponent>() ?: false,
        wasFaceDown = state.projectedState.isFaceDown(snapshot.entityId),
        battlefieldEntryTimestamp = container
            ?.get<BattlefieldEntryTimestampComponent>()?.timestamp,
        name = state.projectedState.getName(snapshot.entityId)
            ?: if (state.projectedState.isFaceDown(snapshot.entityId)) {
                null
            } else {
                container?.get<CardComponent>()?.name
            },
    )
}

/**
 * Whether [entityId] is still the battlefield object represented by [snapshot]. A snapshot with
 * no stamp is never considered a live-object identity witness. A damage reference without an
 * event-time incarnation cannot distinguish an original object from a same-id replacement, so it
 * must use LKI or fail closed. This keeps the no-bare-id rule explicit even for legacy payloads.
 */
fun GameState.isCapturedBattlefieldObjectLive(entityId: EntityId, snapshot: EntitySnapshot): Boolean {
    if (entityId !in getBattlefield() || snapshot.entityId != entityId) return false
    val currentStamp = getEntity(entityId)?.get<BattlefieldEntryTimestampComponent>()?.timestamp
    return snapshot.battlefieldEntryTimestamp != null &&
        currentStamp != null &&
        currentStamp == snapshot.battlefieldEntryTimestamp
}

/**
 * Whether [snapshot] carries a usable event-time incarnation witness for [entityId]. A matching
 * entity id without the battlefield-entry stamp is not enough to distinguish a departed object,
 * a same-id replacement, or malformed legacy damage data.
 */
fun EntitySnapshot.isStampedFor(entityId: EntityId): Boolean =
    this.entityId == entityId &&
        (objectIncarnationStamp != null || battlefieldEntryTimestamp != null)

/**
 * Return this snapshot only when it is an event-time identity witness for [entityId]. Damage-role
 * consumers use this nullable form so an id-only legacy snapshot cannot accidentally authorize a
 * read from the current entity or a same-id replacement.
 */
fun EntitySnapshot?.stampedFor(entityId: EntityId): EntitySnapshot? =
    this?.takeIf { it.isStampedFor(entityId) }

/**
 * Whether two event-time snapshots describe the same object incarnation. A matching entity id is
 * not sufficient: this engine reuses ids across zone changes, and a missing stamp is therefore
 * unknown rather than an implicit match. Battlefield entry stamps take precedence over the
 * zone-independent object stamp so a stack object can never match a battlefield replacement that
 * happens to have the same numeric timestamp.
 */
fun EntitySnapshot?.matchesIncarnation(
    other: EntitySnapshot?,
    entityId: EntityId,
): Boolean {
    val expected = this.stampedFor(entityId) ?: return false
    val actual = other.stampedFor(entityId) ?: return false
    return if (expected.battlefieldEntryTimestamp != null || actual.battlefieldEntryTimestamp != null) {
        expected.battlefieldEntryTimestamp != null &&
            actual.battlefieldEntryTimestamp != null &&
            expected.battlefieldEntryTimestamp == actual.battlefieldEntryTimestamp
    } else {
        expected.objectIncarnationStamp != null &&
            actual.objectIncarnationStamp != null &&
            expected.objectIncarnationStamp == actual.objectIncarnationStamp
    }
}

/**
 * One permanent's last-known information, complete enough for
 * [com.wingedsheep.engine.handlers.PredicateEvaluator.matchesSnapshot]: the state-aware
 * [captureEntitySnapshots] (token-ness, name) plus the projected type line, keywords and
 * card-definition id that call's invariant asks for, and the combat status (attacking / blocking)
 * that [com.wingedsheep.engine.handlers.PredicateEvaluator.matchesSnapshot] answers state
 * predicates from. Take it *before* the event that may remove the
 * permanent — a self-sacrifice cost, the damage that kills it — while the projection still has it.
 */
fun captureLastKnown(state: GameState, entityId: EntityId): EntitySnapshot {
    val container = state.getEntity(entityId)
    return captureEntitySnapshots(listOf(entityId), state).single().copy(
        typeLine = projectedTypeLine(state, entityId),
        keywords = state.projectedState.getKeywords(entityId),
        cardDefinitionId = container?.get<CardComponent>()?.cardDefinitionId,
        copyTriggeredAbilities = captureCopyTriggeredAbilities(state, entityId),
        textChanges = TextChanges.of(state, entityId),
        wasAttacking = container?.has<AttackingComponent>() ?: false,
        wasBlocking = container?.has<BlockingComponent>() ?: false,
        attachmentIds = attachmentIdsOf(state, entityId),
    )
}

/**
 * The Auras/Equipment attached to [entityId] right now, in attachment order — the value frozen into
 * [EntitySnapshot.attachmentIds] and [com.wingedsheep.engine.core.DamageDealtEvent.sourceAttachmentIds]
 * so an attachment trigger still finds its host after a state-based action tears the link down.
 */
fun attachmentIdsOf(state: GameState, entityId: EntityId?): List<EntityId> =
    entityId?.let { state.getEntity(it)?.get<AttachmentsComponent>()?.attachedIds }.orEmpty()

/**
 * The permanent's **projected** type line: its printed types overlaid with whatever continuous
 * effects have granted or replaced (an animated artifact reads "Artifact Creature", a Vehicle
 * crewed this turn reads "Artifact Creature — Vehicle"). Falls back to the printed type line when
 * the entity has no projection entry, and returns null when it has no [CardComponent] at all.
 *
 * Caller must invoke this BEFORE any zone change, while the projection entry still exists. It is
 * the single value frozen into [EntitySnapshot.typeLine], so the zone-exit path
 * (`ZoneTransitionService`) and the cost-time path (`ActivateAbilityHandler`'s
 * [com.wingedsheep.engine.state.components.stack.ActivatedAbilityOnStackComponent.lastKnownSourceSnapshot])
 * capture the same thing rather than two hand-rolled copies.
 */
fun projectedTypeLine(state: GameState, entityId: EntityId): TypeLine? {
    val base = state.getEntity(entityId)?.get<CardComponent>()?.typeLine ?: return null
    return projectedTypeLine(state, entityId, base)
}

/** [projectedTypeLine] for a caller that already holds the printed [baseTypeLine]. */
fun projectedTypeLine(state: GameState, entityId: EntityId, baseTypeLine: TypeLine): TypeLine {
    val projected = state.projectedState.getProjectedValues(entityId) ?: return baseTypeLine
    val cardTypes = projected.types
        .mapNotNull { runCatching { CardType.valueOf(it) }.getOrNull() }
        .toSet()
        .ifEmpty { baseTypeLine.cardTypes }
    val supertypes = projected.types
        .mapNotNull { runCatching { Supertype.valueOf(it) }.getOrNull() }
        .toSet()
    return baseTypeLine.copy(
        cardTypes = cardTypes,
        subtypes = projected.subtypes.map { Subtype(it) }.toSet(),
        supertypes = supertypes,
    )
}

fun List<EntitySnapshot>.snapshotFor(id: EntityId): EntitySnapshot? =
    firstOrNull { it.entityId == id }

val List<EntitySnapshot>.entityIds: List<EntityId>
    get() = map { it.entityId }

/** Freeze copy-added rules text while its battlefield text-changing effects still apply. */
fun captureCopyTriggeredAbilities(state: GameState, entityId: EntityId): List<com.wingedsheep.sdk.scripting.TriggeredAbility> {
    val container = state.getEntity(entityId) ?: return emptyList()
    val abilities = container.get<CardComponent>()?.copyTriggeredAbilities.orEmpty()
    if (abilities.isEmpty()) return abilities
    if (container.has<com.wingedsheep.engine.state.components.identity.FaceDownComponent>() ||
        state.projectedState.hasLostAllAbilities(entityId)
    ) return emptyList()
    val replacement = TextChanges.of(state, entityId)
        ?: return abilities
    return abilities.map { it.applyTextReplacement(replacement) }
}
