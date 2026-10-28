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
        versionCode = 19
        versionName = "1.4.4"
        testInstrumentationRunner = "com.stealth.rootexecutor.FeatureSmokeInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("magisk/tubing-app-trigger"))
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/automationAssets"))
}

tasks.named("preBuild") { dependsOn(bundleAutomationModule) }

dependencies {
    implementation("com.android.tools.build:apksig:8.7.3")
    implementation("net.java.dev.jna:jna:5.15.0@aar")
    testImplementation("junit:junit:4.13.2")
}
