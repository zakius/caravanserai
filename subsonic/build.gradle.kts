// The client for Subsonic and OpenSubsonic servers, the library index and its sync. Only the Subsonic app (the
// subsonic flavor) uses this module (docs/subsonic.md, Two apps).
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

// The tests romanize Japanese names, so they read the slim dictionary the same way :core's tests do.
evaluationDependsOn(":core")
tasks.test {
    useJUnitPlatform()
    dependsOn(project(":core").tasks.named("sudachiSlim"))
    systemProperty("roma.sudachi", File(project(":core").extra["sudachiSlimAssets"] as File, "system_core.dic").path)
}
