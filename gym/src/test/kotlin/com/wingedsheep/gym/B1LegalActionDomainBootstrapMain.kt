package com.wingedsheep.gym

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Fresh-JVM bootstrap for the deep characterization. Windows keeps the original rules-engine jar
 * open through the parent test worker, so the first process creates a patched shadow jar and the
 * second process runs with that jar first on its classpath.
 */
fun main() {
    if (System.getProperty("b1.legalDomain.grandchild") == "true") {
        runWithPatchedClassOutputs()
    } else {
        runWithShadowRulesEngineJar()
    }
}

private fun runWithShadowRulesEngineJar() {
    val tempDirectory = Files.createTempDirectory("b1-legal-domain-shadow")
    val shadowJar = tempDirectory.resolve("rules-engine.jar")
    B1ObservationBytecodeInstrumentation.writePatchedRulesEngineJarForTest(shadowJar)
    val javaExecutable = javaExecutable()
    val classpath = shadowJar.toString() + File.pathSeparator + System.getProperty("java.class.path")
    val outputDir = requireNotNull(System.getProperty("b1.scaling.outputDir")) {
        "Bootstrap requires b1.scaling.outputDir"
    }
    val jfrPath = Path.of(outputDir, "deep", "b1-legal-action-domain-profile.jfr")
    Files.createDirectories(jfrPath.parent)
    val profileArg = if (System.getProperty("b1.profile") == "true") {
        "-XX:StartFlightRecording=filename=$jfrPath,settings=profile,dumponexit=true"
    } else {
        null
    }
    val command = listOf(
        javaExecutable,
        profileArg,
        "-cp",
        classpath,
        "-Db1.legalDomain.grandchild=true",
        "-Db1.scaling.runMode=legal-domain-deep",
        "-Db1.scaling.repetitions=${System.getProperty("b1.scaling.repetitions", "3")}",
        "-Db1.scaling.warmupSteps=${System.getProperty("b1.scaling.warmupSteps", "256")}",
        "-DpreC1.history=${System.getProperty("preC1.history", "false")}",
        "-Db1.profile=${System.getProperty("b1.profile", "false")}",
        "-Db1.scaling.outputDir=$outputDir",
        "com.wingedsheep.gym.B1LegalActionDomainBootstrapMainKt",
    ).filterNotNull()
    try {
        val process = ProcessBuilder(command)
            .directory(File(System.getProperty("user.dir")))
            .inheritIO()
            .start()
        check(process.waitFor() == 0) {
            "Shadow-jar legal-action bootstrap failed with exit code ${process.exitValue()}"
        }
    } finally {
        Files.deleteIfExists(shadowJar)
        Files.deleteIfExists(tempDirectory)
    }
}

private fun runWithPatchedClassOutputs() {
    val session = B1LegalActionDomainProbe.start()
    val instrumentation = B1ObservationBytecodeInstrumentation.installLegalActionDomainDeep(
        includeRulesEngineJar = false,
    )
    try {
        val runnerClass = Class.forName(
            "com.wingedsheep.gym.B1ScalingMeasurementTestKt",
            true,
            Thread.currentThread().contextClassLoader,
        )
        val runner = runnerClass.getDeclaredMethod(
            "runB1LegalActionDomainDeepMeasurementWithInstalledInstrumentation",
            B1LegalActionDomainProbe.Session::class.java,
            B1ObservationBytecodeInstrumentation.Handle::class.java,
        )
        runner.invoke(null, session, instrumentation)
    } catch (failure: Throwable) {
        runCatching { B1LegalActionDomainProbe.stop(session) }
        runCatching { instrumentation.close() }
        throw failure
    }
}

private fun javaExecutable(): String = Path.of(
    System.getProperty("java.home"),
    "bin",
    if (System.getProperty("os.name").contains("win", ignoreCase = true)) "java.exe" else "java",
).toString()
