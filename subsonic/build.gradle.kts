// Subsonic and OpenSubsonic servers (oasis first): the client, the library index and its sync. Only the Subsonic app
// (the subsonic flavor) has it (docs/subsonic.md, Two apps).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_21 } }

dependencies {
    api(project(":core"))
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":core")))
}

// The tests romanize Japanese names: the slim dictionary, as :core's tests read it.
evaluationDependsOn(":core")
tasks.test {
    useJUnitPlatform()
    dependsOn(project(":core").tasks.named("sudachiSlim"))
    systemProperty("roma.sudachi", File(project(":core").extra["sudachiSlimAssets"] as File, "system_core.dic").path)
}
