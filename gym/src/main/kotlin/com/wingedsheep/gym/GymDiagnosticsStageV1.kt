package com.wingedsheep.gym

import com.wingedsheep.rundiagnostics.StageRefV1

/** Operational stage vocabulary owned by the authoritative Gym adapter. */
public object GymDiagnosticsStageV1 {
    private const val FAMILY = "argentum-gym-diagnostics-stage@v1"

    public val INITIALIZING: StageRefV1 = stage("INITIALIZING")
    public val RESETTING: StageRefV1 = stage("RESETTING")
    public val RUNNING: StageRefV1 = stage("RUNNING")

    private fun stage(name: String): StageRefV1 = StageRefV1(
        stageFamilySchemaIdentity = FAMILY,
        stageName = name,
    )
}
