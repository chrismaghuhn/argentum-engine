package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.rundiagnostics.StageRefV1

/** Operational stage vocabulary for A5/A6 trajectory admission and publication. */
public object TrajectoryDiagnosticsStageV1 {
    private const val FAMILY = "argentum-trajectory-diagnostics-stage@v1"

    public val INITIALIZING: StageRefV1 = stage("INITIALIZING")
    public val ADMITTING: StageRefV1 = stage("ADMITTING")
    public val FINALIZING: StageRefV1 = stage("FINALIZING")
    public val PUBLISHED: StageRefV1 = stage("PUBLISHED")

    private fun stage(name: String): StageRefV1 = StageRefV1(
        stageFamilySchemaIdentity = FAMILY,
        stageName = name,
    )
}

/** Operational stage vocabulary for the strict A7 reader. */
public object ReaderDiagnosticsStageV1 {
    private const val FAMILY = "argentum-trajectory-reader-diagnostics-stage@v1"

    public val INITIALIZING: StageRefV1 = stage("INITIALIZING")
    public val PREFLIGHT: StageRefV1 = stage("PREFLIGHT")
    public val OPEN: StageRefV1 = stage("OPEN")
    public val STREAMING: StageRefV1 = stage("STREAMING")
    public val COMPLETE: StageRefV1 = stage("COMPLETE")

    private fun stage(name: String): StageRefV1 = StageRefV1(
        stageFamilySchemaIdentity = FAMILY,
        stageName = name,
    )
}
