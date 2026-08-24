package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.mechanics.layers.ProjectedState
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
