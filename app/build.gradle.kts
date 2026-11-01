plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// The Japanese dictionary is a separate app, :dict-app; roma needs its version and where to fetch it.
evaluationDependsOn(":core")
val dictVersion = project(":core").extra["dictRelease"] as String

android {
    namespace = "com.zakius.roma"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        versionCode = 58
        versionName = "0.1.57"
        // `-Proma.details=true`: a release build that shows the debug details too (song origins, Lidarr's progress).
        buildConfigField("boolean", "SHOW_DETAILS", (findProperty("roma.details") == "true").toString())
        // The dictionary app, gzipped. `-Proma.dictBase=<url>` points a build at another folder holding it.
        val dictBase = (findProperty("roma.dictBase") as String?) ?: "https://github.com/zakius/roma/releases/download/dict-$dictVersion"
        buildConfigField("String", "DICT_URL", "\"$dictBase/roma-dict-$dictVersion.apk.gz\"")
        buildConfigField("int", "DICT_VERSION", (project(":core").extra["dictCode"] as Int).toString())
        buildConfigField("String", "DICT_PACKAGE", "\"com.zakius.roma.dict\"")
        manifestPlaceholders["dictPackage"] = "com.zakius.roma.dict"
    }

    // Two apps from one codebase (docs/subsonic.md, Two apps): roma ytm (the youtube flavor), YouTube Music only,
    // released on GitHub; and roma (subsonic), for Subsonic servers and Google Play, whose APK carries no YouTube code
    // at all. Each flavor brings its own music source.
    flavorDimensions += "app"
    productFlavors {
        // Each app's name, id and slug: gradle.properties.
        for (app in listOf("youtube", "subsonic")) create(app) {
            dimension = "app"
            val name = property("${app}App.name") as String
            val slug = property("${app}App.slug") as String
            applicationId = property("${app}App.id") as String
            buildConfigField("String", "APP_NAME", "\"$name\"")
            buildConfigField("String", "SLUG", "\"$slug\"")
            manifestPlaceholders += mapOf("appLabel" to name, "appIcon" to if (app == "youtube") "@android:drawable/ic_media_play" else "@drawable/app_icon", "linkScheme" to slug)
        }
        // Testing only: `-Proma.refuseVisionOs=true` acts as if YouTube refused the VISIONOS client (Streams.refuseVisionOs),
        // so songs play through InnerTubeX's other clients. Never set for a release.
        getByName("youtube").buildConfigField("boolean", "REFUSE_VISIONOS", (findProperty("roma.refuseVisionOs") == "true").toString())
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "DICT_PACKAGE", "\"com.zakius.roma.dict.debug\"")
            manifestPlaceholders["dictPackage"] = "com.zakius.roma.dict.debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { compose = true; buildConfig = true }
    // The screens and playback logic, shared with the desktop app (:desktop); each flavor adds its source's own.
    sourceSets["main"].kotlin.directories += "../shared/src/main/kotlin"
    sourceSets["youtube"].kotlin.directories += "../shared/src/youtube/kotlin"
    sourceSets["subsonic"].kotlin.directories += "../shared/src/subsonic/kotlin"
    // The dictionary download, as on the desktop.
    sourceSets["subsonic"].kotlin.directories += "../shared/src/portable/kotlin"
    // Only the app's languages: libraries bring strings for about 80 locales.
    androidResources { localeFilters += setOf("en", "pl") }
    packaging {
        resources { excludes += setOf("META-INF/*.md", "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin", "META-INF/*.version",
            // Sudachi reads its part-of-speech table only in its command-line tool.
            "pos.csv",
            // Four copies of the Apache License 2.0, which assets/LICENSE-2.0.txt already carries.
            "META-INF/androidx/**/LICENSE.txt") }
        // androidx.graphics.path's native PathIterator for Android 13 and older: Compose ships it, R8 removes every caller.
        // Without it the APK has no native code, so it suits every architecture.
        jniLibs { excludes += "**/libandroidx.graphics.path.so" }
    }
}

dependencies {
    implementation(project(":core"))
    "youtubeImplementation"(project(":youtube"))
    "subsonicImplementation"(project(":subsonic"))

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.12.1")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    implementation("androidx.media3:media3-exoplayer:1.11.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.2") // ASUS FlipFont skip, App.kt
}
