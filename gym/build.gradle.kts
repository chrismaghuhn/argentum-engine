plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

tasks.named<Test>("test") {
    // The exact-pair acceptance suite is an explicit heavyweight trust gate, not part of the
    // normal PR/unit-test critical path. Run it through :environmentV1AcceptanceTest instead.
    exclude("**/EnvironmentV1ExactPairAcceptanceTest*")
    // The bounded A9 generation gate is an explicit data-publication run, never an ordinary unit
    // test. Run it through :environmentV1TrustedGenerationTest instead.
    exclude("**/EnvironmentV1TrustedGenerationTest*")
    // Pending-payment contract tests read the immutable locked Commander artifact directly.
    inputs.file(rootProject.layout.projectDirectory.file("docs/ml/curriculum/akiri-v0.1.txt"))
}

tasks.register<Test>("environmentV1AcceptanceTest") {
    description = "Runs the heavy Environment V1 exact-pair acceptance suite."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/EnvironmentV1ExactPairAcceptanceTest*")
}

tasks.register<Test>("environmentV1TrustedGenerationTest") {
    description = "Runs the bounded trusted Environment V1 generation and publication gate."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/EnvironmentV1TrustedGenerationTest*")
    // A single 2,000-step trajectory is intentionally canonicalized as one published episode.
    // Give this opt-in data-integrity gate enough heap without changing ordinary test workers.
    maxHeapSize = "8g"
}

// B1 performance/scaling characterization is opt-in test-only work. Forward its controls to the
// test worker so Gradle's daemon properties cannot silently leave a measurement disabled or stale.
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
        "b1.scaling.gradleTask",
        "b1.scaling.runMode",
        "b1.latency",
        "b1.latency.warmupSteps",
        "b1.latency.outputDir",
        "b1.resetHeavy",
        "b1.resetHeavy.resets",
        "b1.resetHeavy.outputDir",
        "b1.contract",
        "a9.episodeLimit",
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

    // Focused pending-payment tests use the existing Rules scenario fixture only to reach a real
    // engine decision boundary; this does not create a production dependency.
    testImplementation(testFixtures(project(":rules-engine")))
    testImplementation(project(":mtg-sets"))
    // Integration-only A9 generation composition root. Production :gym remains independent from
    // :game-server and :gym-trainer; only this test source set crosses both existing boundaries.
    testImplementation(project(":game-server")) {
        isTransitive = false
    }
    testImplementation(project(":gym-trainer"))
    testImplementation(libs.kotestRunner)
    testImplementation(libs.kotestAssertions)
    testImplementation(libs.kotestProperty)
    testImplementation(kotlin("reflect"))
    testImplementation("org.ow2.asm:asm:9.7.1")
}
