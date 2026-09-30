plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    // Production transported-replay verification composition: this module owns the seam between
    // gym-trainer's offline admission contract and game-server's replay reconstruction
    // infrastructure. It deliberately does NOT make game-server depend on gym-trainer — the
    // documented game-server build seam keeps replay infrastructure free of trajectory storage
    // contracts, and gym-trainer stays a Spring-free pure library. This leaf composes both
    // without being a dependency of either, so the DAG stays valid.
    implementation(project(":gym"))
    implementation(project(":gym-trainer"))
    // game-server is consumed non-transitively (the :gym test pattern): its optional Spring/
    // database runtime is irrelevant to replay verification and resolves with versionless
    // constraints under a dependency-only consumer. Everything the verifier actually touches
    // is declared explicitly below.
    implementation(project(":game-server")) {
        isTransitive = false
    }
    implementation(project(":rules-engine"))
    implementation(project(":mtg-sdk"))
    implementation(project(":mtg-sets"))
    implementation(libs.bundles.kotlinxEcosystem)
    runtimeOnly(libs.slf4jApi)
    implementation(kotlin("reflect"))

    testImplementation(libs.kotestRunner)
    testImplementation(libs.kotestAssertions)
    testImplementation(kotlin("reflect"))
}
