// Plain JVM module: no Android imports, so a desktop client could reuse it. Both apps' shared core: romanization,
// lyrics rules, radio filters, scrobbling. Nothing here knows a music service: YouTube Music is :youtube, Subsonic :subsonic.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        optIn.add("kotlin.ExperimentalStdlibApi")
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    // Japanese words and readings. The dictionary isn't in the jar: see sudachiDict below.
    implementation("com.worksap.nlp:sudachi:0.8.2")
    // Sudachi's own trie, for roma's dictionary format (RomaDictionary.kt); the version Sudachi depends on.
    implementation("com.worksap.nlp:jdartsclone:1.2.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    testImplementation(kotlin("test"))
    // Android supplies android.icu; on the JVM the tests use ICU4J (jvmIcu, in the test fixtures).
    testFixturesImplementation("com.ibm.icu:icu4j:78.1")
}

// SudachiDict "core", downloaded once into the Gradle cache (202 MB unpacked, too big for git). Its slim build is the
// apps' download (dictZip); the JVM tests read it from here. To update: change the version and the zip's sha256 (docs/design.md).
val sudachiVersion = "20261015"
val sudachiSha256 = "5cf5cb7f1ea943a988e2fb5d285a9a8b3538861faefa7a2604c2cd18c387f503"
val sudachiDir = File(gradle.gradleUserHomeDir, "caches/roma/sudachi-core-$sudachiVersion")
extra["sudachiAssets"] = File(sudachiDir, "assets")
extra["sudachiVersion"] = sudachiVersion
// The dictionary's release: the SudachiDict version, then roma's slimming revision (bump it when Slim.kt changes what
// it writes, or the download's form). The apps check the system_core.dic they unpack against dictSha256, the slim build's (dictZip checks it).
val dictRelease = "20261015.3"
val dictSha256 = "d93d7dd9232cc88f061458f3a283a7737e328aa186b2446c613f747f10d23de2"
check(dictRelease.startsWith("$sudachiVersion.")) { "dictRelease must start with sudachiVersion" }
extra["dictRelease"] = dictRelease
extra["dictSha256"] = dictSha256

tasks.register("sudachiDict") {
    val assets = File(sudachiDir, "assets")
    outputs.dir(assets)
    doLast {
        if (File(assets, "system_core.dic").exists()) return@doLast
        val zip = File(sudachiDir, "core.zip")
        sudachiDir.mkdirs()
        val url = URI("https://github.com/WorksApplications/SudachiDict/releases/download/v$sudachiVersion/sudachi-dictionary-$sudachiVersion-core.zip")
        url.toURL().openStream().use { input ->
            zip.outputStream().use { output -> input.copyTo(output) }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(zip.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == sudachiSha256) { "SudachiDict zip: sha256 $digest, expected $sudachiSha256" }
        project.copy {
            from(zipTree(zip)) {
                include("*/system_core.dic", "*/LEGAL")
                eachFile { path = if (name == "LEGAL") "sudachi-LEGAL.txt" else name }
            }
            into(assets)
            includeEmptyDirs = false
        }
        zip.delete()
    }
}

// The dictionary roma ships: SudachiDict core minus what roma never reads (:dict-tool, docs/design.md). The renumbered
// unknown-word definitions and config go into this module's resources; the dictionary into dictZip.
val slimDir = File(sudachiDir, "slim-$dictRelease")
val slimDict = File(slimDir, "dict")
val slimConfig = File(slimDir, "config")
extra["sudachiSlimAssets"] = slimDict
val sudachiSlim = tasks.register<JavaExec>("sudachiSlim") {
    dependsOn("sudachiDict")
    classpath = project(":dict-tool").extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
    mainClass = "SlimKt"
    maxHeapSize = "6g"
    args(File(sudachiDir, "assets/system_core.dic").path, slimDir.path + "/build", sudachiVersion)
    outputs.dirs(slimDict, slimConfig)
    onlyIf { !File(slimDict, "system_core.dic").exists() }
    doLast {
        val built = File(slimDir, "build")
        slimDict.mkdirs()
        slimConfig.mkdirs()
        File(built, "roma-sudachi.json").renameTo(File(slimConfig, "roma-sudachi.json"))
        File(built, "roma-unk.def").renameTo(File(slimConfig, "roma-unk.def"))
        File(sudachiDir, "assets/sudachi-LEGAL.txt").copyTo(File(slimDict, "sudachi-LEGAL.txt"), true)
        File(built, "system_core.dic").renameTo(File(slimDict, "system_core.dic"))
        built.deleteRecursively()
    }
}
evaluationDependsOn(":dict-tool")
tasks.named("sudachiSlim") { dependsOn(":dict-tool:classes") }

// What the apps download (DICT_URL), published as the GitHub release dict-<dictRelease>: the slim dictionary with the
// notices that must travel with it (Apache-2.0 §4, docs/design.md), in build/dict/roma-dict-<dictRelease>.zip.
tasks.register("dictZip") {
    dependsOn(sudachiSlim)
    val notice = rootProject.file("dict-tool/notice/NOTICE")
    val files = listOf(notice, rootProject.file("app/src/main/assets/LICENSE-2.0.txt"), File(slimDict, "sudachi-LEGAL.txt"), File(slimDict, "system_core.dic"))
    val out = layout.buildDirectory.file("dict/roma-dict-$dictRelease.zip")
    inputs.files(files)
    outputs.file(out)
    doLast {
        // The NOTICE names the SudachiDict release the dictionary is modified from.
        check("SudachiDict core $sudachiVersion " in notice.readText()) { "$notice: name SudachiDict core $sudachiVersion" }
        val digest = MessageDigest.getInstance("SHA-256").digest(files.last().readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == dictSha256) { "system_core.dic: sha256 $digest, expected dictSha256 $dictSha256" }
        ZipOutputStream(out.get().asFile.outputStream()).use { zip ->
            zip.setLevel(9)
            for (f in files) {
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { input -> input.copyTo(zip) }
            }
        }
    }
}

// The slim build's config files are committed in src/main/resources, so building roma never downloads or slims the
// dictionary; only the tests and dictZip need it. The tests check the committed copies are the current ones.
tasks.test {
    useJUnitPlatform()
    dependsOn(sudachiSlim)
    systemProperty("roma.sudachi", File(slimDict, "system_core.dic").path)
    doFirst {
        for (name in listOf("roma-sudachi.json", "roma-unk.def")) {
            val built = File(slimConfig, name)
            val committed = file("src/main/resources/$name")
            check(built.readText() == committed.readText()) { "$committed is out of date: copy $built over it" }
        }
    }
}
