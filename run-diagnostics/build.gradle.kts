plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    application
}

dependencies {
    implementation(libs.kotlinxSerialization)

    testImplementation(libs.kotestRunner)
    testImplementation(libs.kotestAssertions)
}

application {
    mainClass.set("com.wingedsheep.rundiagnostics.supervisor.SupervisorMainKt")
}
