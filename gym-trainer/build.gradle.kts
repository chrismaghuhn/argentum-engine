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
