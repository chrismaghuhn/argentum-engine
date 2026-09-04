package com.wingedsheep.gameserver.replay

/** How faithfully a replay re-simulation reproduced the recorded game. */
enum class ReplayFidelity {
    /** Every recorded action applied, and every checkpoint matched. */
    EXACT,

    /** Every recorded action applied, but the replay proof is absent or incomplete. */
    UNVERIFIED,

    /** The re-simulation stopped at an action or checkpoint mismatch. */
    DIVERGED,
}
