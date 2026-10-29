import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val packagingJdkHome = providers.gradleProperty("stint.packagingJdk")
    .orElse(providers.environmentVariable("STINT_PACKAGING_JDK"))
    .orElse(providers.systemProperty("java.home"))

kotlin {
    androidTarget()
    jvm("desktop")
    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(libs.compose.runtime.saveable)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.window)
            implementation(libs.androidx.lifecycle.viewmodel.ktx)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.ktor.client.okhttp)
        }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.androidx.test.core.ktx)
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.rules)
                implementation(libs.androidx.test.ext.junit)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.cio)
                implementation(libs.coroutines.swing)
            }
        }
    }
}

android {
    namespace = "dev.stint.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.stint.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("debug") {
        manifest.srcFile("src/androidDebug/AndroidManifest.xml")
        res.srcDir("src/androidDebug/res")
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

compose.desktop {
    application {
        mainClass = "dev.stint.app.MainKt"
        javaHome = packagingJdkHome.get()
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "Stint"
            packageVersion = "1.2.0"
            modules("java.net.http", "jdk.crypto.ec")
            macOS {
                bundleID = "dev.stint.app"
                iconFile.set(rootProject.file("branding/Stint.icns"))
            }
        }
    }
}

// A changed JDK must rebuild the bundled runtime, even when application code is unchanged.
tasks.matching { it.name == "createRuntimeImage" }.configureEach {
    inputs.property("stintPackagingJdkHome", packagingJdkHome)
    inputs.file(packagingJdkHome.map { file(it).resolve("release") })
        .withPropertyName("stintPackagingJdkRelease")
}

tasks.register<JavaExec>("configureDesktopConnection") {
    group = "application"
    description = "Stores the environment-provided connection in the desktop credential store."
    dependsOn("desktopMainClasses")
    mainClass.set("dev.stint.app.ConfigureConnectionKt")
    classpath(
        kotlin.targets.getByName("desktop").compilations.getByName("main").output.allOutputs,
        configurations.getByName("desktopRuntimeClasspath"),
    )
}
