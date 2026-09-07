package com.wingedsheep.gameserver.replay

import com.wingedsheep.rundiagnostics.StageRefV1

/** Operational stage vocabulary owned by the replay verification adapter. */
public object ReplayDiagnosticsStageV1 {
    private const val FAMILY = "argentum-replay-diagnostics-stage@v1"

    public val INITIALIZING: StageRefV1 = stage("INITIALIZING")
    public val VERIFYING: StageRefV1 = stage("VERIFYING")
    public val COMPLETE: StageRefV1 = stage("COMPLETE")

    private fun stage(name: String): StageRefV1 = StageRefV1(
        stageFamilySchemaIdentity = FAMILY,
        stageName = name,
    )
}
