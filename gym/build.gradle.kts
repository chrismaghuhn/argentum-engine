plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

tasks.named<Test>("test") {
    // The exact-pair acceptance suite is an explicit heavyweight trust gate, not part of the
    // normal PR/unit-test critical path. Run it through :environmentV1AcceptanceTest instead.
    exclude("**/EnvironmentV1ExactPairAcceptanceTest*")
}

tasks.register<Test>("environmentV1AcceptanceTest") {
    description = "Runs the heavy Environment V1 exact-pair acceptance suite."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/EnvironmentV1ExactPairAcceptanceTest*")
}

// B1 performance profiling is an opt-in test-only workload. Forward its controls to the test
// worker so Gradle's daemon properties cannot silently leave the profiler disabled or stale.
tasks.withType<Test>().configureEach {
    for (property in listOf(
        "b1.profile",
        "b1.characterize",
        "b1.workload",
        "b1.mode",
        "b1.outputDir",
        "b1.scaling",
        "b1.scaling.repetitions",
        "b1.scaling.warmupSteps",
        "b1.scaling.outputDir",
        "b1.scaling.isolation",
        "b1.scaling.isolationOutputDir",
        "kotest.filter.tests",
    )) {
        System.getProperty(property)?.let { systemProperty(property, it) }
    }
}

dependencies {
    // Wraps the rules engine with a stateful RL/MCTS-friendly environment.
    implementation(project(":rules-engine"))
    implementation(project(":mtg-sdk"))
    implementation(project(":ai"))

    implementation(libs.bundles.kotlinxEcosystem)
    // :ai's deck generation (SealedDeckGenerator → Draftsim autobuilder) logs via slf4j. We don't
    // compile against it — the dependency is transitive — but the API must be on the runtime
    // classpath, which propagates to non-Spring consumers like :gym-trainer. Spring consumers
    // (:gym-server) already supply a binding.
    runtimeOnly(libs.slf4jApi)

    testImplementation(project(":mtg-sets"))
    testImplementation(libs.kotestRunner)
    testImplementation(libs.kotestAssertions)
    testImplementation(libs.kotestProperty)
    testImplementation(kotlin("reflect"))
    testImplementation("org.ow2.asm:asm:9.7.1")
}
