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
    // The serialized A8 closure audit reopens large finalized shards and is also an explicit
    // opt-in trust gate. Run it through :environmentV1DecisionFamilyClosureAuditTest instead.
    exclude("**/EnvironmentV1DecisionFamilyClosureAuditTest*")
    // Pending-payment contract tests read the immutable locked Commander artifact directly.
    inputs.file(rootProject.layout.projectDirectory.file("docs/ml/curriculum/akiri-v0.1.txt"))
    // The KA06 transported-replay characterization (=_01 oracle) and the _02 production-verifier
    // integration spec are explicit opt-in trust gates that spawn their own verifier JVM
    // processes. Run them through :kaggleActor06CharacterizationTest.
    exclude("**/KaggleActor06*")
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

tasks.register<Test>("environmentV1DecisionFamilyClosureAuditTest") {
    description = "Audits serialized A9 decision families against the accepted A8 closure matrix."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/EnvironmentV1DecisionFamilyClosureAuditTest*")
    // A7 shard validation recomputes large trajectory identities before the closure scan.
    maxHeapSize = "8g"
}

tasks.register<Test>("kaggleActor04SmokeTest") {
    description = "Runs the opt-in tiny provider/publication smoke characterization."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/KaggleActor04ProviderSmokeTest*")
    maxHeapSize = "4g"
}

tasks.register<Test>("kaggleActor05CharacterizationTest") {
    description = "Runs the opt-in bounded actor characterization and sizing measurement."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/KaggleActor05ProviderCharacterizationTest*")
    maxHeapSize = "8g"
}

tasks.register<Test>("kaggleActor07BenchmarkTest") {
    description = "Runs the opt-in shard-generation throughput benchmark (-Dka07.benchmark=true)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/KaggleActor07ShardGenerationBenchmarkTest*")
    // Same heap as the A9 generation gate: one 2,000-step episode is canonicalized in memory.
    maxHeapSize = "8g"
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}

tasks.register<Test>("phase1SelfPlayMeasureTest") {
    description = "Opt-in: engine AI vs engine AI on the locked Commander matchup (-Dphase1.measure=true)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/Phase1EngineAiCommanderSelfPlayMeasurementTest*")
    maxHeapSize = "4g"
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}

tasks.register<Test>("phase1SelfPlayCollectTest") {
    description = "Opt-in Phase 1 teacher data: engine AI self-play samples (-Dphase1.collect=true)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/Phase1SelfPlayCollectTest*")
    // Each worker holds one Commander game; size -Dphase1.workers against this heap.
    maxHeapSize = System.getProperty("phase1.heap") ?: "12g"
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}

tasks.register<Test>("phase1TournamentTest") {
    description = "Opt-in Phase 1 match between engine AI and/or model checkpoints (-Dphase1.tournament=true)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/Phase1TournamentTest*")
    maxHeapSize = System.getProperty("phase1.heap") ?: "12g"
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}

tasks.register<Test>("phase1DaggerCollectTest") {
    description = "Opt-in P1 DAgger round: engine-AI teacher labels model-reached positions (-Dphase1.dagger=true)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/Phase1DaggerCollectTest*")
    maxHeapSize = System.getProperty("phase1.heap") ?: "12g"
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}

tasks.register<Test>("phase1PpoCollectTest") {
    description = "Opt-in P1 PPO rollouts: a sampling checkpoint plays a league (-Dphase1.ppo=true)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/Phase1PpoCollectTest*")
    maxHeapSize = System.getProperty("phase1.heap") ?: "12g"
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}

tasks.register<Test>("engineAiDeterminismTraceTest") {
    description = "Opt-in: seeded engine AI self-play trace for behavior parity (-Dperf.trace=true)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/EngineAiDeterminismTraceTest*")
    maxHeapSize = "6g"
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}

tasks.register<Test>("kaggleActor06CharacterizationTest") {
    description = "Runs the opt-in transported-replay reconstruction authority characterization."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/KaggleActor06TransportedReplayCharacterizationTest*")
    include("**/KaggleActor06ProductionVerifierIntegrationTest*")
    // The producer and two verifier worker JVMs each load the full card catalog and rebuild
    // replay state; give this opt-in trust gate the same heap as the A9 generation gate.
    maxHeapSize = "8g"
}

// B1 performance/scaling characterization is opt-in test-only work. Forward its controls to the
// test worker so Gradle's daemon properties cannot silently leave a measurement disabled or stale.
tasks.withType<Test>().configureEach {
    systemProperty("ka04.gradleProjectRoot", rootProject.projectDir.absolutePath)
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
        "preC1.history",
        "a9.episodeLimit",
        "a9.auditDatasetRoot",
        "ka04.enabled",
        "ka04.engineSourceCommit",
        "ka04.repositoryRoot",
        "ka04.outputRoot",
        "ka04.publicationRoot",
        "ka04.profiles",
        "ka05.enabled",
        "ka05.engineSourceCommit",
        "ka05.repositoryRoot",
        "ka05.outputRoot",
        "ka05.publicationRoot",
        "ka05.sizes",
        "ka05.concurrencies",
        "ka05.initialBytesPerEpisodeEstimate",
        "ka05.providerOutputCapBytes",
        "ka05.initialScratchEstimateBytes",
        "ka06.repositoryRoot",
        "perf.trace",
        "perf.games",
        "perf.workers",
        "perf.out",
        "ka07.benchmark",
        "ka07.episodes",
        "ka07.outputFile",
        "phase1.measure",
        "phase1.games",
        "phase1.maxSteps",
        "phase1.profile",
        "phase1.outputFile",
        "phase1.traceFrom",
        "phase1.collect",
        "phase1.firstGame",
        "phase1.workers",
        "phase1.baseSeed",
        "phase1.outputDir",
        "phase1.tournament",
        "phase1.seatA",
        "phase1.seatB",
        "phase1.results",
        "phase1.python",
        "phase1.showcase",
        "phase1.showcaseDir",
        "phase1.dagger",
        "phase1.checkpoint",
        "phase1.opponent",
        "phase1.labelRate",
        "phase1.teacherPlayouts",
        "phase1.ppo",
        "phase1.league",
        "phase1.policyProcesses",
        "phase1.gameTimeoutSeconds",
    )) {
        System.getProperty(property)?.let { systemProperty(property, it) }
    }
}

dependencies {
    // Wraps the rules engine with a stateful RL/MCTS-friendly environment.
    implementation(project(":rules-engine"))
    implementation(project(":mtg-sdk"))
    implementation(project(":ai"))
    // Optional operational diagnostics callbacks; the nullable seam keeps the workload path
    // free of status serialization and filesystem publication when diagnostics are disabled.
    api(project(":run-diagnostics"))

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
    // KA06 _02 integration: the production process-isolated verifier composes game-server replay
    // infrastructure with gym-trainer admission contracts in a leaf module. Test-source-only use,
    // following the established non-transitive integration pattern above.
    testImplementation(project(":offline-replay-verifier"))
    testImplementation(libs.kotestRunner)
    testImplementation(libs.kotestAssertions)
    testImplementation(libs.kotestProperty)
    testImplementation(kotlin("reflect"))
    testImplementation("org.ow2.asm:asm:9.7.1")
}
