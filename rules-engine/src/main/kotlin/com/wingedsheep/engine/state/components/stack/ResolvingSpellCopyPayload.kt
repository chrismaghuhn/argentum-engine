package com.wingedsheep.engine.state.components.stack

import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.Effect
import kotlinx.serialization.Serializable

/**
 * Immutable snapshot of a spell while its resolution-time copy choice is pending.
 *
 * A resolving spell leaves the stack before a [com.wingedsheep.sdk.scripting.effects.MayEffect]
 * can resume. Keeping the card characteristics, the effective spell effect, cast-time spell
 * component, and original target bindings together lets the generic copy path recreate the spell
 * without reading components that were removed by the zone change. The snapshot is internal game
 * state; the player still supplies both the may answer and any replacement targets through the
 * normal decision protocol.
 */
@Serializable
data class ResolvingSpellCopyPayload(
    val sourceSpellId: EntityId,
    val card: CardComponent,
    val spell: SpellOnStackComponent,
    val targets: TargetsComponent? = null,
    val effectiveSpellEffect: Effect? = null,
    val cantBeCopied: Boolean = false,
)
