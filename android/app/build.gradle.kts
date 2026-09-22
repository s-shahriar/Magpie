import org.gradle.internal.os.OperatingSystem
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/** Where the Rust workspace lives, relative to the Gradle project. */
val rustRoot: File = rootProject.file("..")
val jniLibsDir: File = layout.projectDirectory.file("src/main/jniLibs").asFile
val uniffiOut: File = layout.buildDirectory.dir("generated/uniffi").get().asFile

/**
 * Builds `magpie-core` for Android and drops the .so into jniLibs.
 *
 * Only arm64 by default — the K20 Pro is arm64 and building three ABIs triples
 * the release build for no benefit. Pass -PallAbis to build them all.
 */
val cargoBuild by tasks.registering(Exec::class) {
    group = "rust"
    description = "Compile magpie-core with cargo-ndk"
    workingDir = rustRoot
    val abis = if (project.hasProperty("allAbis")) {
        listOf("arm64-v8a", "armeabi-v7a", "x86_64")
    } else {
        listOf("arm64-v8a")
    }
    val args = mutableListOf("cargo", "ndk")
    abis.forEach { args += listOf("-t", it) }
    args += listOf("-o", jniLibsDir.absolutePath, "build", "--release", "-p", "magpie-core")
    commandLine(if (OperatingSystem.current().isWindows) listOf("cmd", "/c") + args else args)
}

/** Generates the Kotlin bindings from the compiled library. */
val uniffiBindings by tasks.registering(Exec::class) {
    group = "rust"
    description = "Generate UniFFI Kotlin bindings"
    dependsOn(cargoBuild)
    workingDir = rustRoot
    doFirst { uniffiOut.mkdirs() }
    commandLine(
        "cargo", "run", "--release", "--bin", "uniffi-bindgen", "--",
        "generate",
        "--library", File(jniLibsDir, "arm64-v8a/libmagpie_core.so").absolutePath,
        "--language", "kotlin",
        "--out-dir", uniffiOut.absolutePath,
    )
}

/**
 * Strips the Rust library *after* bindings are generated.
 *
 * `strip` cannot be enabled in Cargo's release profile: uniffi-bindgen reads the
 * interface metadata out of the library's symbols and silently emits nothing
 * when they are gone. Stripping here instead keeps the build working and takes
 * the .so from 11 MB to ~4 MB; `--strip-unneeded` leaves every exported FFI
 * symbol in place, which is all the app needs at runtime.
 */
val stripNativeLibs by tasks.registering {
    group = "rust"
    description = "Strip debug symbols from the Rust .so"
    dependsOn(uniffiBindings)
    doLast {
        // Resolved from the SDK rather than android.ndkDirectory, which throws
        // unless ndkVersion is pinned — and pinning it breaks other machines.
        val sdk = android.sdkDirectory
        val ndk = File(sdk, "ndk").listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
        val host = if (OperatingSystem.current().isMacOsX) "darwin-x86_64" else "linux-x86_64"
        val strip = ndk?.let { File(it, "toolchains/llvm/prebuilt/$host/bin/llvm-strip") }
        if (strip == null || !strip.exists()) {
            logger.warn("llvm-strip not found at $strip — shipping unstripped library")
            return@doLast
        }
        jniLibsDir.walkTopDown().filter { it.extension == "so" }.forEach { so ->
            providers.exec {
                commandLine(strip.absolutePath, "--strip-unneeded", so.absolutePath)
            }.result.get()
        }
    }
}

android {
    namespace = "com.syed.magpie"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.syed.magpie"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("release") {
            val props = rootProject.file("keystore.properties")
            if (props.exists()) {
                val p = Properties()
                props.inputStream().use { p.load(it) }
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Signed with the release key on purpose. Debug and release
            // otherwise carry different signatures, so swapping between them
            // needs an uninstall — which wipes the WebView cookie jar and
            // signs the user out of Facebook and Drive. Restoring that jar
            // afterwards does not work: WebView encrypts cookie values with a
            // key tied to the install, so a copied database is purged on the
            // next launch.
            if (rootProject.file("keystore.properties").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (rootProject.file("keystore.properties").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    buildFeatures { compose = true; buildConfig = true }

    sourceSets["main"].java.srcDir(uniffiOut)
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

// Bindings must exist before Kotlin compiles.
tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }
    .configureEach { dependsOn(uniffiBindings) }

// Packaging must see the stripped library, not the one bindgen read.
tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLibFolders") }
    .configureEach { dependsOn(stripNativeLibs) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.webkit)
    implementation(libs.jna) { artifact { type = "aar" } }
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
