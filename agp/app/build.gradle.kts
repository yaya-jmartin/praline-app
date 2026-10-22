import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// One source of truth: the sources, assets, resources and manifest at the repository root.
val repo: File = rootDir.parentFile
val sourceManifest = File(repo, "app/AndroidManifest.xml").readText()
fun manifestValue(name: String): String = Regex("android:$name=\"([^\"]+)\"").find(sourceManifest)!!.groupValues[1]

// The Android Gradle Plugin takes the package, versions and SDK levels from this file instead of the manifest.
val agpManifest = layout.buildDirectory.file("agp-manifest/AndroidManifest.xml").get().asFile
agpManifest.parentFile.mkdirs()
agpManifest.writeText(sourceManifest
    .replace(Regex("\\s+package=\"[^\"]+\""), "")
    .replace(Regex("\\s+android:versionCode=\"[^\"]+\""), "")
    .replace(Regex("\\s+android:versionName=\"[^\"]+\""), "")
    .replace(Regex("\\s*<uses-sdk[^>]*/>"), ""))

android {
    namespace = "com.yaya.praline"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yaya.praline"
        minSdk = manifestValue("minSdkVersion").toInt()
        targetSdk = manifestValue("targetSdkVersion").toInt()
        versionCode = manifestValue("versionCode").toInt()
        versionName = manifestValue("versionName")
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile(agpManifest)
            // the ML Kit text reader exists only in this build (Google's servers aren't reachable from the hand-made one)
            java.srcDirs(File(repo, "src/main/kotlin"), "src/ocr/kotlin")
            res.srcDirs(File(repo, "app/res"))
            assets.srcDirs(File(repo, "app/assets"))
        }
        // test photos are read by path from src/test/resources, so tests run from the repository root
        getByName("test") { java.srcDirs(File(repo, "src/test/kotlin")) }
    }

    // The release key comes from GitHub's secrets only (the workflow writes it to a temporary file);
    // without them the release APK is built unsigned, as before.
    val keystore = System.getenv("PRALINE_KEYSTORE")?.takeIf { it.isNotEmpty() && File(it).exists() }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = File(keystore)
            storePassword = System.getenv("PRALINE_KEY_PASSWORD")
            keyAlias = "praline"
            keyPassword = System.getenv("PRALINE_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // tests run from agp/app, where Robolectric finds AGP's resource APK; repoFile() reaches the repo files
                it.maxHeapSize = "3g"
                it.testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
            }
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        htmlReport = true
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation("com.google.zxing:core:3.5.3")
    // on-device text recognition with the Latin model inside the app: no download, no picture sent anywhere
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
