package com.wingedsheep.engine.event

import com.wingedsheep.engine.mechanics.durations.GrantDurationGate
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.Duration
import com.wingedsheep.sdk.scripting.TriggeredAbility
import kotlinx.serialization.Serializable

/**
 * A triggered ability that has been granted to an entity temporarily.
 *
 * Used for effects like Commando Raid that grant triggered abilities
 * until end of turn. Stored in GameState.grantedTriggeredAbilities
 * and checked by TriggerDetector when looking up abilities for entities.
 *
 * @property entityId The entity that has the granted ability
 * @property ability The triggered ability that was granted
 * @property duration How long the grant lasts
 * @property sourceId The permanent (or spell) whose effect made the grant, when there is one.
 *   Only the *source-keyed* "for as long as …" durations read it — `WhileSourceOnBattlefield`,
 *   `WhileSourceTapped`, `WhileSourceAttachedToAffected` — which
 *   [com.wingedsheep.engine.mechanics.sba.permanent.EndedDurationExpiryCheck] uses to end the
 *   grant. Null for a grant made by a spell that is already gone (Makeshift Mannequin) and for
 *   the token-creation grants, whose duration is `Permanent`.
 */
@Serializable
data class GrantedTriggeredAbility(
    val entityId: EntityId,
    val ability: TriggeredAbility,
    val duration: Duration,
    val sourceId: EntityId? = null
)

/**
 * The triggered abilities [GameState.grantedTriggeredAbilities] gives [entityId] right now: every
 * grant on that id whose "for as long as …" duration still holds ([GrantDurationGate] — Makeshift
 * Mannequin's rider ends the moment its counter leaves).
 *
 * The one reading shared by the live trigger lookup ([TriggerAbilityResolver]) and the damage-time
 * snapshot ([com.wingedsheep.engine.handlers.effects.DamageUtils.captureDamageRoleSnapshot]), so the
 * two can't disagree about which grants an object had. Grants are keyed by entity id alone and
 * outlive the object's departure (they are only dropped when that id re-enters the battlefield), so
 * this answers for whatever object the id names *now* — a reader that must not mix up incarnations
 * reads the frozen snapshot instead.
 */
fun GameState.activeGrantedTriggeredAbilities(entityId: EntityId): List<TriggeredAbility> {
    if (grantedTriggeredAbilities.isEmpty()) return emptyList()
    return grantedTriggeredAbilities.mapNotNull { grant ->
        grant.ability.takeIf {
            grant.entityId == entityId &&
                GrantDurationGate.holds(this, grant.entityId, grant.sourceId, grant.duration)
        }
    }
}
