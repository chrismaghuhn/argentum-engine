plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    // Self-play + MCTS over :gym. Pure Kotlin library — no Spring.
    implementation(project(":gym"))
    implementation(project(":rules-engine"))
    implementation(project(":mtg-sdk"))
    implementation(project(":ai"))
    // Optional operational diagnostics callbacks owned by the trajectory and reader seams.
    api(project(":run-diagnostics"))

    implementation(libs.bundles.kotlinxEcosystem)
    implementation(kotlin("reflect"))

    testImplementation(project(":mtg-sets"))
    testImplementation(libs.kotestRunner)
    testImplementation(libs.kotestAssertions)
    testImplementation(libs.kotestProperty)
}

tasks.named<Test>("test") {
    // The strict downloaded-output re-import gate needs a Kaggle download root and a large
    // heap; it is opt-in, exactly like :environmentV1TrustedGenerationTest in :gym.
    exclude("**/Ka05DownloadedOutputReimportTest*")
}

tasks.register<Test>("kaggleActor05DownloadedReimportTest") {
    description = "Strictly re-imports a downloaded Kaggle actor publication envelope."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/Ka05DownloadedOutputReimportTest*")
    // Reopening finalized bounded shards needs the same heap as the generation gate.
    maxHeapSize = "8g"
}
