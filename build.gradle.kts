import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins { kotlin("jvm") version "2.1.21" }
repositories { maven("https://maven-central.storage-download.googleapis.com/maven2/"); mavenCentral() }
dependencies {
    compileOnly(files("/home/claude/dl/android-all-15-robolectric-12650502.jar"))
    implementation("com.google.zxing:core:3.5.3")
}
java { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.addAll("-Xlambdas=class", "-Xjdk-release=1.8", "-Xsam-conversions=class", "-Xstring-concat=inline",
            "-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }
}
tasks.register<Copy>("copyLibs") { from(configurations.runtimeClasspath); into(layout.buildDirectory.dir("libs-rt")) }
val pg by configurations.creating
dependencies { pg("com.guardsquare:proguard-base:7.6.1") }
tasks.register<Copy>("copyPg") { from(pg); into(layout.buildDirectory.dir("pg-libs")) }
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1") { exclude(group = "androidx.test"); exclude(group = "androidx.test.espresso") }
    testCompileOnly(files("/home/claude/dl/android-all-14-robolectric-10818077.jar"))
    testRuntimeOnly(files("/home/claude/dl/android-all-14-robolectric-10818077.jar"))
}
tasks.test {
    systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
    systemProperty("robolectric.enabledSdks", "34")
    testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; showStandardStreams = true }
}
tasks.named<JavaCompile>("compileTestJava") { classpath += files("/home/claude/dl/android-all-14-robolectric-10818077.jar"); options.release.set(null as Int?); sourceCompatibility = "1.8"; targetCompatibility = "1.8" }
tasks.register<Test>("testShrunk") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = files("build/apk/app.jar") + sourceSets.test.get().runtimeClasspath.filter {
        !it.path.contains("classes/kotlin/main") && !it.name.startsWith("kotlin-stdlib")
    }
    systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
    testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
