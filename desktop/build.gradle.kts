// roma for Linux and Windows: the app's screens and playback logic (shared/, compiled into :app too) on Compose for
// Desktop, with roma's own audio decoding and the system's Chromium-family browser for BotGuard and sign-in (docs/design.md).
// :desktop builds roma ytm, the YouTube Music app. roma, the Subsonic app, is this script too, as :desktop-subsonic
// (settings.gradle.kts): its own sources (src/subsonic, shared/src/subsonic), name and packages, and no YouTube code,
// browser or CEF (docs/subsonic.md, Two apps).
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Arrays

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

val flavor = if (project.name == "desktop-subsonic") "subsonic" else "youtube"
val youtube = flavor == "youtube"
val src = rootProject.file("desktop/src")
// Each app's name and slug: gradle.properties.
val appName = property("${flavor}App.name") as String
val slug = property("${flavor}App.slug") as String

// One version for both apps, read from the Android build where release.sh and the release workflow look for it.
val appGradle = rootProject.file("app/build.gradle.kts").readText()
val versionName = Regex("""versionName = "(.+)"""").find(appGradle)!!.groupValues[1]
val versionCode = Regex("""versionCode = (\d+)""").find(appGradle)!!.groupValues[1]
evaluationDependsOn(":core")
val dictVersion = project(":core").extra["dictRelease"] as String
val dictSha256 = project(":core").extra["dictSha256"] as String

java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_21 }
    sourceSets["main"].kotlin.setSrcDirs(listOf("$src/main/kotlin", "$src/$flavor/kotlin", rootProject.file("shared/src/main/kotlin"), rootProject.file("shared/src/$flavor/kotlin"), rootProject.file("shared/src/portable/kotlin")))
    sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/buildConfig"))
    sourceSets["test"].kotlin.setSrcDirs(listOfNotNull("$src/test/kotlin", "$src/testYoutube/kotlin".takeIf { youtube }))
}
// From the Android app: the player's icons (vector drawables, which Compose for Desktop reads too), BotGuard's page
// (roma's) and the licence notices; the window's icon.
sourceSets["main"].resources.setSrcDirs(listOfNotNull(rootProject.file("app/src/main/res"), rootProject.file("app/src/main/assets"), rootProject.file("app/src/youtube/assets").takeIf { youtube }, "$src/$flavor/resources"))
sourceSets["main"].resources.include("drawable/*.xml", "po_token.html", "NOTICE", "*.txt", "icon.png")

// What Android's BuildConfig holds, for the shared code.
val buildConfig = tasks.register("buildConfig") {
    val out = layout.buildDirectory.file("generated/buildConfig/BuildConfig.kt")
    val refuse = findProperty("roma.refuseVisionOs") == "true"
    val dictBase = (findProperty("roma.dictBase") as String?) ?: "https://github.com/zakius/roma/releases/download/dict-$dictVersion"
    val jars = if (youtube) cefJars else provider { "" }
    val name = appName
    val dataSlug = slug
    val own = youtube
    inputs.property("v", "$versionName $versionCode $dictVersion $dictSha256 $refuse $dictBase $name $dataSlug $own")
    inputs.property("cef", jars)
    outputs.file(out)
    doLast {
        // roma's own: the CEF jars Browser.kt fetches, the VISIONOS test switch (Streams), its GitHub updates.
        val roma = if (!own) "" else """
            |    const val CEF_JARS = "${jars.get()}"
            |    const val REFUSE_VISIONOS = $refuse
            |""".trimMargin() + "\n"
        out.get().asFile.writeText("""
            |package com.zakius.roma
            |
            |object BuildConfig {
            |    const val APP_NAME = "$name"
            |    const val SLUG = "$dataSlug"
            |    const val UPDATES = $own
            |    const val VERSION_NAME = "$versionName"
            |    const val VERSION_CODE = $versionCode
            |    const val DICT_VERSION = "$dictVersion"
            |    const val DICT_URL = "$dictBase/roma-dict-$dictVersion.zip"
            |    const val DICT_SHA256 = "$dictSha256"
            |""".trimMargin() + "\n" + roma + "}\n")
    }
}
tasks.named("compileKotlin") { dependsOn(buildConfig) }

// roma-aac, the audio-only ffmpeg for the songs YouTube has only in AAC (tools/build-ffmpeg.sh), built once per version
// of that script into the Gradle cache. Linux builds both (Windows' by cross-compiling); elsewhere -Proma.aacDir=<dir>
// hands over prebuilt ones (<dir>/linux/roma-aac, <dir>/windows/roma-aac.exe), as the release's desktop jobs do.
val aacScript = MessageDigest.getInstance("SHA-256").digest(rootProject.file("tools/build-ffmpeg.sh").readBytes()).take(6).joinToString("") { "%02x".format(it) }
val aacDir = (findProperty("roma.aacDir") as String?)?.let(::File) ?: File(gradle.gradleUserHomeDir, "caches/roma/roma-aac-$aacScript")
val windowsHost = System.getProperty("os.name").startsWith("Windows")
// One task builds it for both apps: the Subsonic app's build takes :desktop's.
if (!youtube) evaluationDependsOn(":desktop")
val romaAac = if (!youtube) project(":desktop").tasks.named("romaAac") else tasks.register("romaAac") {
    val script = rootProject.file("tools/build-ffmpeg.sh")
    inputs.file(script)
    outputs.dir(aacDir)
    onlyIf { !windowsHost }
    doLast {
        for ((os, exe) in listOf("linux" to "roma-aac", "windows" to "roma-aac.exe")) {
            if (File(aacDir, "$os/$exe").exists()) continue
            providers.exec { commandLine(script.path, os, File(aacDir, os).path) }.result.get()
        }
    }
}
// The package's own resources (compose.application.resources.dir): per OS, roma-aac.
val appResources = layout.buildDirectory.dir("appResources")
val copyAppResources = tasks.register<Sync>("copyAppResources") {
    dependsOn(romaAac)
    from(File(aacDir, "linux")) { into("linux") }
    from(File(aacDir, "windows")) { into("windows") }
    into(appResources)
}
tasks.matching { it.name.startsWith("prepareAppResources") }.configureEach { dependsOn(copyAppResources) }

// ICU4J without the locale data roma never reads (dates, zones, collation, units, names, line breaks, word dictionaries
// but Thai's) or the transliteration rules its transliterators never load (icuRules): it only transliterates. ProGuard
// then drops the classes transliteration doesn't reach. 15.2 to 2.1 MB. :desktop:icuCheck shows the released jar gives
// the full jar's output on every assigned code point, for every transliterator roma uses.
val icu = configurations.create("icu")
val icuTrim = sourceSets.create("icuTrim") { java.setSrcDirs(listOf("$src/icuTrim/java")) }
configurations[icuTrim.implementationConfigurationName].extendsFrom(icu)
sourceSets.test { compileClasspath += icuTrim.output; runtimeClasspath += icuTrim.output }
val icuRules = tasks.register<JavaExec>("icuRules") {
    classpath = icuTrim.runtimeClasspath
    mainClass = "com.zakius.roma.IcuTrim"
    val out = layout.buildDirectory.file("icu/root.res")
    outputs.file(out)
    args(out.get().asFile.path)
}
val cef = configurations.create("cef")
// "<path on Maven Central> <sha256>" per jar CEF's Java side needs, for BuildConfig.CEF_JARS.
val cefJars = provider {
    cef.resolvedConfiguration.resolvedArtifacts.joinToString(";") { a ->
        val id = a.moduleVersion.id
        val name = "${id.name}-${id.version}" + (a.classifier?.let { "-$it" } ?: "") + ".${a.extension}"
        val sha = MessageDigest.getInstance("SHA-256").digest(a.file.readBytes()).joinToString("") { "%02x".format(it) }
        "${id.group.replace('.', '/')}/${id.name}/${id.version}/$name $sha"
    }
}
val icuSlim = tasks.register<Jar>("icuSlim") {
    archiveFileName = "icu4j-slim.jar"
    destinationDirectory = layout.buildDirectory.dir("icu")
    val data = "com/ibm/icu/impl/data/icudata"
    val keepTop = setOf("root", "pool", "res_index", "supplementalData", "metadata", "icuver", "icustd", "keyTypeData", "langInfo")
    from({ zipTree(icu.singleFile) }) {
        exclude("$data/zone/**", "$data/coll/**", "$data/unit/**", "$data/lang/**", "$data/curr/**", "$data/region/**", "$data/rbnf/**")
        exclude("$data/brkitr/cjdict.dict", "$data/brkitr/line*.brk", "com/ibm/icu/impl/duration/impl/data/**")
        exclude("$data/*.spp", "$data/confusables.cfu", "$data/uts46.nrm", "$data/nfkc_*.nrm", "$data/unames.icu")
        exclude("$data/brkitr/burmesedict.dict", "$data/brkitr/khmerdict.dict", "$data/brkitr/laodict.dict") // Thai's is read
        exclude("$data/translit/root.res")
        // Locale bundles at the top (ko.res, en_GB.res, …); ICU's own files stay.
        exclude { e -> e.path.matches(Regex("$data/[^/]+\\.res")) && e.name.removeSuffix(".res") !in keepTop }
    }
    from(icuRules) { into("$data/translit") }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    // Transliteration (Chinese pinyin, Greek, the Cyrillic safety net): Android has it as android.icu.
    icu("com.ibm.icu:icu4j:78.1")
    implementation(files(icuSlim))
    testImplementation(files(icu)) // icuCheck compares against it
    // Opus, what YouTube serves nearly every song in, decoded in pure Java. The rest (AAC) goes to roma-aac.
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    // roma's Chromium for BotGuard and sign-in when the system has no Chromium-family browser: CEF. Its Java side isn't
    // packaged either: Browser.kt downloads these jars with CEF on first need (BuildConfig.CEF_JARS pins them).
    // 146, not 152: CEF 152 exits at start on Linux (code 28), off-screen or not (2026-10-29).
    if (youtube) {
        cef("me.friwi:jcefmaven:146.0.10")
        compileOnly("me.friwi:jcefmaven:146.0.10")
        testImplementation("me.friwi:jcefmaven:146.0.10")
    }
    implementation(project(if (youtube) ":youtube" else ":subsonic"))
}

compose.desktop {
    application {
        mainClass = "com.zakius.roma.MainKt"
        // The release distribution (createReleaseDistributable) drops what roma never calls, as finimal's does.
        buildTypes.release.proguard {
            configurationFiles.from(listOfNotNull(rootProject.file("desktop/proguard-rules.pro"), rootProject.file("desktop/proguard-youtube.pro").takeIf { youtube }))
            obfuscate = true
        }
        nativeDistributions {
            appResourcesRootDir.set(appResources)
            // Linux ships as a .deb and .rpm on the distribution's Java (tools/linux-packages.sh); Windows as an .msi
            // with its own Java ("full") and a small one that finds or downloads Java (tools/windows-packages.sh, from
            // the release app image; this .msi target only brings the WiX toolset).
            targetFormats(TargetFormat.Msi)
            packageName = slug
            packageVersion = versionName.split('.').let { (it + listOf("0", "0")).take(3) }.joinToString(".")
            description = if (youtube) "YouTube Music, every title and lyric romanized" else "Your music server, every title and lyric romanized"
            vendor = "zakius"
            licenseFile.set(rootProject.file("LICENSE"))
            // The full build's Java, beyond what Compose adds (java.base, java.desktop, java.logging, jdk.crypto.ec):
            // java.net.http for the browser's DevTools socket, jdk.httpserver for Last.fm's sign-in, java.management
            // for CEF's process. suggestRuntimeModules also lists jdk.javadoc (the whole compiler, 25 MB) and
            // java.instrument, which only optional code refers to. The JDK must have jmods (Temurin 24+ has none).
            modules("java.management", "java.net.http", "java.sql", "jdk.httpserver", "jdk.unsupported")
            windows {
                iconFile.set(rootProject.file("packaging/$slug.ico"))
                menuGroup = appName
                perUserInstall = true
                shortcut = true
                // Fixed, so a new MSI (full or small) replaces the installed version instead of installing beside it.
                upgradeUuid = if (youtube) "6d0b7c3e-1f5a-4d7e-9a43-6f8f0b2c9e41" else "3f2a9c5e-8b14-4d6f-a0e7-5c9d2b71e486"
            }
        }
    }
}

// The full build's Java with its classes compressed (zip-6): 32 MB less, for 10 ms more at start (1.5%, on Linux).
tasks.withType<AbstractJLinkTask>().configureEach { freeArgs.add("--compress=zip-6") }

dependencies { testImplementation(kotlin("test")) }
// The unit tests (FedProcessTest); the other test sources are checks run as their own tasks below.
tasks.test { useJUnitPlatform() }
tasks.register<JavaExec>("decodeCheck") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.zakius.roma.DecodeCheckKt"
    dependsOn(copyAppResources)
    systemProperty("roma.aac", (findProperty("aac") as String?) ?: File(aacDir, if (windowsHost) "windows/roma-aac.exe" else "linux/roma-aac").path)
    args((findProperty("files") as String? ?: "").split(',').filter { it.isNotBlank() })
}
if (youtube) tasks.register<JavaExec>("botGuardCheck") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.zakius.roma.BotGuardCheckKt"
    if (project.hasProperty("cef")) systemProperty("cef", "")
}

// Skia's library (Linux; on Windows its icudtl.dat) carries ICU's whole data (10.4 MB) and reads only its line, word and grapheme breaking. The rest
// (collation, time zones, locale names, converters, the Chinese/Japanese word dictionary) goes: its table of contents
// lists only what stays. In the library the blob keeps its place and size and what's freed is zeros; Windows' file ends
// after what stays (10.5 to 1.7 MB). :desktop:skiaCheck shows text lays out as with the full data.
fun trimSkiaIcu(lib: File) {
    val bytes = lib.readBytes()
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    // ICU's common data: a header ("CmnD" at 12), then the table: count, (name offset, data offset) pairs, the names,
    // the items in name order, each up to where the next begins. Offsets count from the table.
    val header = (0 until bytes.size - 16).single { i ->
        bytes[i + 2] == 0xda.toByte() && bytes[i + 3] == 0x27.toByte() && String(bytes, i + 12, 4) == "CmnD"
    }
    val toc = header + b.getShort(header).toInt()
    val count = b.getInt(toc)
    fun name(k: Int) = String(bytes, toc + b.getInt(toc + 4 + 8 * k), 64).substringBefore('\u0000').substringAfter('/')
    fun data(k: Int) = toc + b.getInt(toc + 8 + 8 * k)
    val keepTop = setOf("root", "pool", "res_index", "supplementalData", "metadata", "icuver", "icustd", "keyTypeData", "langInfo")
    val kept = (0 until count).filter { k ->
        val n = name(k)
        if (n.startsWith("brkitr/")) n != "brkitr/cjdict.dict"
        else '/' !in n && (n.endsWith(".icu") || n.endsWith(".nrm") || n.removeSuffix(".res") in keepTop)
    }
    // The last item's size isn't recorded; it's a locale's (zu_ZA.res), which goes.
    check(kept.size in 20 until count && count - 1 !in kept) { "${lib.name}: unexpected ICU data" }
    val out = bytes.copyOf()
    val o = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    Arrays.fill(out, toc + 4 + 8 * kept.size, toc + 4 + 8 * count, 0)
    Arrays.fill(out, data(0), data(count - 1), 0)
    o.putInt(toc, kept.size)
    var at = data(0)
    kept.forEachIndexed { i, k ->
        o.putInt(toc + 4 + 8 * i, b.getInt(toc + 4 + 8 * k))
        o.putInt(toc + 8 + 8 * i, at - toc)
        System.arraycopy(bytes, data(k), out, at, data(k + 1) - data(k))
        at += data(k + 1) - data(k)
    }
    lib.writeBytes(if (lib.name == "icudtl.dat") out.copyOf(at) else out)
}
// Linux: also Skia's symbol table (3 MB). Its exported symbols stay; the Java runtime keeps its own for crash logs.
tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
    doLast {
        val libs = outputs.files.asFileTree.matching { include("**/lib/app/*skiko*.so", "**/app/icudtl.dat") }.files
        check(libs.isNotEmpty()) { "no ICU data of Skia's in ${outputs.files.files}" }
        libs.forEach(::trimSkiaIcu)
        if (System.getProperty("os.name") != "Linux") return@doLast
        val strip = ProcessBuilder(listOf("strip", "--strip-unneeded") + libs.map { it.path }).inheritIO().start()
        check(strip.waitFor() == 0) { "strip failed" }
    }
}

// Compose's default ProGuard rules keep all of Kotlin's standard library, which roma mostly doesn't call (finimal's
// measurement: 5.6 MB of jars), all of Skia's Java side, and every serializer whole and by name, which keeps the YouTube
// models' package names too. Skia's native code needs only what proguard-rules.pro keeps; kotlinx.serialization, its
// own rules there, as Android's R8 reads them.
afterEvaluate {
    val proguard = tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask>()
    val stock = proguard.first().defaultComposeRulesFile.get().asFile
    val trimmed = layout.buildDirectory.file("compose-proguard-rules.pro")
    val trimRules = tasks.register("trimComposeProguardRules") {
        dependsOn("unpackDefaultComposeDesktopJvmApplicationResources")
        inputs.file(stock)
        outputs.file(trimmed)
        doLast {
            val broad = listOf("-keep class kotlin.** { *; }\n", "-keep class org.jetbrains.skia.** { *; }\n",
                "-keep class org.jetbrains.skiko.** { *; }\n", "-keep class **\$\$serializer {\n    *;\n}\n")
            val rules = stock.readText()
            check(broad.all { it in rules }) { "Compose's default rules changed what they keep whole; check this filter" }
            trimmed.get().asFile.writeText(broad.fold(rules) { r, b -> r.replace(b, "") })
        }
    }
    proguard.configureEach {
        dependsOn(trimRules)
        defaultComposeRulesFile.set(trimmed)
    }
}
val skiaFull = tasks.register<Copy>("skiaFull") {
    from({ configurations["runtimeClasspath"].filter { it.name.startsWith("skiko-awt-runtime-") }.map { zipTree(it) } })
    include("*.so", "*.dll", "icudtl.dat")
    into(layout.buildDirectory.dir("skia-full"))
}
tasks.register<JavaExec>("skiaCheck") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.zakius.roma.SkiaCheckKt"
    val app = "compose/binaries/main-release/app/$slug/" + if (windowsHost) "app" else "lib/app" // jpackage's layouts
    args(skiaFull.get().destinationDir.path, layout.buildDirectory.dir(app).get().asFile.path)
    dependsOn(skiaFull, "createReleaseDistributable")
}
tasks.register<JavaExec>("icuCheck") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.zakius.roma.IcuCheckKt"
    val app = layout.buildDirectory.dir("compose/binaries/main-release/app/$slug/lib/app")
    argumentProviders += CommandLineArgumentProvider {
        listOf(icu.singleFile.path, app.get().asFile.listFiles()!!.single { it.name.startsWith("icu4j-slim") }.path)
    }
    dependsOn("createReleaseDistributable")
}
