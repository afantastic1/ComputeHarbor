/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val useOpenClMnn = project.findProperty("pairMnnOpenCL")?.toString()?.equals("false", ignoreCase = true) != true
val mnnRuntimeVariant = if (useOpenClMnn) "opencl" else "cpu"

android {
    namespace = "com.nv.pair"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.nv.pair"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["pairMnnOpenCL"] = useOpenClMnn.toString()
        testInstrumentationRunnerArguments["pairMnnModelDir"] =
            project.findProperty("pairMnnDeviceModelDir")?.toString().orEmpty()
        testInstrumentationRunnerArguments["pairModelId"] =
            project.findProperty("pairMnnDeviceModelId")?.toString().orEmpty()
        val modelSwitchFixture = project.findProperty("pairMnnModelSwitchDeviceDir")?.toString().orEmpty()
        val m10Acceptance = project.findProperty("pairM10Acceptance")?.toString()?.equals("true", ignoreCase = true) == true
        val m65Acceptance = project.findProperty("pairM65Acceptance")?.toString()?.equals("true", ignoreCase = true) == true
        val pairingPersistenceAcceptance = project.findProperty("pairPersistenceAcceptance")?.toString()?.equals("true", ignoreCase = true) == true
        val acceptanceCount = listOf(m10Acceptance, m65Acceptance, pairingPersistenceAcceptance).count { it }
        require(acceptanceCount <= 1) { "Only one PAIR acceptance runner can run in the same instrumentation invocation." }
        val excludedInstrumentedTests = mutableListOf<String>()
        if (m10Acceptance) {
            testInstrumentationRunnerArguments["pairHostIp"] = project.findProperty("pairM10PcAddress")?.toString().orEmpty()
            testInstrumentationRunnerArguments["class"] = "com.nv.pair.M10PcToAndroidMnnInstrumentedTest"
        } else {
            excludedInstrumentedTests += "com.nv.pair.M10PcToAndroidMnnInstrumentedTest"
        }
        if (m65Acceptance) {
            testInstrumentationRunnerArguments["pairHostIp"] = project.findProperty("pairM65PcAddress")?.toString().orEmpty()
            testInstrumentationRunnerArguments["pairModelId"] = project.findProperty("pairM65ModelId")?.toString().orEmpty()
            testInstrumentationRunnerArguments["pairEngine"] = project.findProperty("pairM65Engine")?.toString().orEmpty()
            testInstrumentationRunnerArguments["class"] =
                "com.nv.pair.M65AndroidToPcRoutingInstrumentedTest#streamsPcOnlyModelThroughAndroidEngineFacade"
        } else {
            excludedInstrumentedTests += "com.nv.pair.M65AndroidToPcRoutingInstrumentedTest"
        }
        if (pairingPersistenceAcceptance) {
            testInstrumentationRunnerArguments["pairHostIp"] = project.findProperty("pairPersistencePcAddress")?.toString().orEmpty()
            testInstrumentationRunnerArguments["class"] =
                "com.nv.pair.PairRuntimeServiceInstrumentedTest#pairedPeerMembershipSurvivesStopAndStart"
        }
        if (modelSwitchFixture.isBlank()) {
            excludedInstrumentedTests += "com.nv.pair.mnn.MnnModelSwitchInstrumentedTest"
        } else {
            testInstrumentationRunnerArguments["pairMnnModelSwitchDir"] = modelSwitchFixture
        }
        testInstrumentationRunnerArguments["notClass"] = excludedInstrumentedTests.joinToString(",")

        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += "-DPAIR_MNN_RUNTIME_VARIANT=$mnnRuntimeVariant"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/pairJniLibs"))
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/mnnJniLibs"))

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

val stagePairNativeBinaries = tasks.register<Exec>("stagePairNativeBinaries") {
    val outputDir = layout.buildDirectory.dir("generated/pairJniLibs").get().asFile
    commandLine(
        "powershell.exe",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        rootProject.file("scripts/stage-native-binaries.ps1").absolutePath,
        "-OutputDir",
        outputDir.absolutePath,
    )
}

tasks.named("preBuild") {
    dependsOn(stagePairNativeBinaries)
}

val mnnArtifactsDir = rootProject.layout.buildDirectory.dir("generated/mnn/arm64-v8a")
val selectedMnnArtifactsDir = mnnArtifactsDir.map { directory ->
    if (useOpenClMnn) directory.dir("opencl") else directory
}
val buildMnnRuntime = tasks.register<Exec>("buildMnnRuntime") {
    inputs.property("mnnSourceRevision", "d407447ed56c4121a11ccbd266dc184ca1ead0c2")
    inputs.property("mnnRuntimeVariant", mnnRuntimeVariant)
    inputs.file(rootProject.file("scripts/build-mnn.ps1"))
    inputs.file(rootProject.file("patches/mnn-request-sampler.patch"))
    outputs.file(selectedMnnArtifactsDir.map { it.file("source-revision.txt") })
    val buildArguments = mutableListOf(
        "powershell.exe",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        rootProject.file("scripts/build-mnn.ps1").absolutePath,
        "-SourceDir",
        rootProject.projectDir.parentFile.resolve("third_party/MNN").absolutePath,
    )
    if (useOpenClMnn) buildArguments += "-IncludeOpenCL"
    commandLine(buildArguments)
}

val stageMnnRuntimeJni = tasks.register<Sync>("stageMnnRuntimeJni") {
    dependsOn(buildMnnRuntime)
    from(selectedMnnArtifactsDir) {
        include("libMNN*.so", "libllm.so", "libc++_shared.so")
    }
    into(layout.buildDirectory.dir("generated/mnnJniLibs/arm64-v8a"))
}

tasks.named("preBuild") {
    dependsOn(stageMnnRuntimeJni)
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(buildMnnRuntime)
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
