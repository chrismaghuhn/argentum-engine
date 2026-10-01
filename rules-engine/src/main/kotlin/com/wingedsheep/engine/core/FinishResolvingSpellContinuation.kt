package com.wingedsheep.engine.core

import com.wingedsheep.engine.state.ObjectRef
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import kotlinx.serialization.Serializable

/** Completes a spell only after all of its resolving effects and choices have finished. */
@Serializable
data class FinishResolvingSpellContinuation(
    val spellObject: ObjectRef,
    val spellComponent: SpellOnStackComponent,
    val cardComponent: CardComponent?,
    /**
     * True once the spell's final zone move has already been handed to the replacement pipeline
     * and is waiting on a replacement decision (a Commander 903.9b "command zone instead" choice,
     * or CR 616 ordering). That pending move performs the physical transition itself, so this
     * frame only reports the resolution once the move completes; it never tries to move the spell
     * a second time.
     */
    val dispositionPending: Boolean = false,
) : AutomaticContinuation
