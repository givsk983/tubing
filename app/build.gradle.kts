import org.apache.tools.ant.filters.FixCrLfFilter
import org.gradle.api.tasks.bundling.Zip

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val bundleAutomationModule by tasks.registering(Zip::class) {
    from(rootProject.file("magisk/tubing-app-trigger")) {
        include("*.sh", "module.prop", "skip_mount")
        filesMatching(listOf("*.sh", "module.prop")) {
            filter(FixCrLfFilter::class, "eol" to FixCrLfFilter.CrLf.newInstance("lf"))
        }
    }
    archiveFileName.set("tubing-app-trigger.zip")
    destinationDirectory.set(layout.buildDirectory.dir("generated/automationAssets"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

android {
    namespace = "com.stealth.rootexecutor"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.stealth.rootexecutor"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "1.4.7"
        testInstrumentationRunner = "com.stealth.rootexecutor.FeatureSmokeInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildTypes {
        create("optimized") {
            initWith(getByName("debug"))
            // Keep the existing signing identity so this APK can update the test builds.
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            matchingFallbacks += "debug"
        }
    }
    packaging {
        // Compress JNI libraries in the APK; Android extracts them on installation.
        // Retain all existing ABIs rather than narrowing device compatibility.
        jniLibs.useLegacyPackaging = true
    }
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("magisk/tubing-app-trigger"))
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/automationAssets"))
}

tasks.named("preBuild") { dependsOn(bundleAutomationModule) }

dependencies {
    implementation(files("libs/liquidglass-v2.0.11.aar"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.android.tools.build:apksig:8.7.3")
    implementation("net.java.dev.jna:jna:5.15.0@aar")
    testImplementation("junit:junit:4.13.2")
}
