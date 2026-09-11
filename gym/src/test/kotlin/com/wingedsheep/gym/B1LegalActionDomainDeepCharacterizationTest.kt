package com.wingedsheep.gym

import io.kotest.core.spec.style.FunSpec
import java.io.File
import java.nio.file.Path
import kotlin.time.Duration.Companion.hours

/**
 * Opt-in control/deep attribution runner for the accepted corpus8 workload. It is intentionally
 * separate from the ordinary B1 scaling test so a measurement run cannot change the normal gate.
 */
class B1LegalActionDomainDeepCharacterizationTest : FunSpec({
    val enabled = System.getProperty("b1.scaling") == "true" &&
        System.getProperty("b1.scaling.runMode") in setOf(
            "legal-domain-control",
            "legal-domain-deep",
            "legal-domain-bootstrap",
        )

    test("characterizes legal action and domain cost without changing semantics").config(
        enabled = enabled,
        timeout = 4.hours,
    ) {
        if (System.getProperty("b1.scaling.runMode") in setOf("legal-domain-deep", "legal-domain-bootstrap")) {
            runB1LegalActionDomainBootstrapJvm()
        } else {
            runB1LegalActionDomainDeepMeasurement()
        }
    }
})

private fun runB1LegalActionDomainBootstrapJvm() {
    val javaExecutable = Path.of(
        System.getProperty("java.home"),
        "bin",
        if (System.getProperty("os.name").contains("win", ignoreCase = true)) "java.exe" else "java",
    ).toString()
    val command = listOf(
        javaExecutable,
        "-cp",
        System.getProperty("java.class.path"),
        "-Db1.scaling.runMode=legal-domain-deep",
        "-Db1.scaling.repetitions=${System.getProperty("b1.scaling.repetitions", "3")}",
        "-Db1.scaling.warmupSteps=${System.getProperty("b1.scaling.warmupSteps", "256")}",
        "-DpreC1.history=${System.getProperty("preC1.history", "false")}",
        "-Db1.profile=${System.getProperty("b1.profile", "false")}",
        "-Db1.scaling.outputDir=${System.getProperty("b1.scaling.outputDir")}",
        "com.wingedsheep.gym.B1LegalActionDomainBootstrapMainKt",
    )
    val process = ProcessBuilder(command)
        .directory(File(System.getProperty("user.dir")))
        .inheritIO()
        .start()
    check(process.waitFor() == 0) {
        "Fresh-JVM legal-action bootstrap failed with exit code ${process.exitValue()}"
    }
}
