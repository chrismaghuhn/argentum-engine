package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.mechanics.layers.ProjectedState
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.model.EntityId

/**
 * Freeze the source's effective subtype set at the actual mana-production seam.
 *
 * A source's printed [com.wingedsheep.sdk.core.TypeLine] is not authoritative while continuous
 * effects are active. Callers must invoke this before tapping or sacrificing the source; payment
 * code must never reconstruct this snapshot from the source after production.
 */
internal fun ProjectedState.productionSourceSubtypes(entityId: EntityId): Set<Subtype> =
    getSubtypes(entityId).map { Subtype.of(it) }.toSet()

/**
 * Recover the production-time subtype snapshot already captured for an activated source whose
 * cost moved it out of the battlefield. An empty set is a known-empty snapshot; null means no
 * such authoritative snapshot was captured and the live projected source may still be consulted
 * at the actual production seam.
 */
internal fun EffectContext.capturedProductionSourceSubtypes(): Set<Subtype>? =
    lastKnownSourceSnapshot
        ?.takeIf { it.entityId == sourceId }
        ?.subtypes
        ?.map { Subtype.of(it) }
        ?.toSet()
