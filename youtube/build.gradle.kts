// YouTube Music for roma ytm: the vendored com.metrolist.innertube sources on top of InnerTubeX (vendored too), over
// HttpURLConnection (core's Http.kt); the stream lookup with its JavaScript player (meow.nyx.quackjs); comments, the
// personal mixes, and the lyrics providers besides LRCLIB. Only roma ytm (the youtube flavor) has it: caravan must
// carry none of it (docs/subsonic.md, Two apps).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_21; optIn.add("kotlin.ExperimentalStdlibApi") } }

dependencies {
    api(project(":core"))
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":core")))
}

evaluationDependsOn(":core")
val sudachiSlim = project(":core").tasks.named("sudachiSlim")
val slimDict = project(":core").extra["sudachiSlimAssets"] as File

tasks.test { useJUnitPlatform() }

tasks.register<JavaExec>("networkCheck") {
    classpath = sourceSets["test"].runtimeClasspath
    dependsOn(sudachiSlim)
    systemProperty("roma.sudachi", File(slimDict, "system_core.dic").path)
    mainClass = (project.findProperty("main") as String?) ?: "roma.core.NetworkCheckKt"
}
