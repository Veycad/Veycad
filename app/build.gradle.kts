import java.security.MessageDigest
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("com.android.application")
}

// MVP instrumentation compiles only the reused real helpers/editor cases, never the
// uiTest fake application. The default complete uiTest source set remains unchanged.
val previewSourceSha = providers.environmentVariable("GIT_SHA").orNull?.also {
    require(it.matches(Regex("[0-9a-f]{40}"))) { "GIT_SHA must be a complete source commit" }
} ?: providers.gradleProperty("sourceSha").orElse("local-unverified").get()

val mvpDeviceTests = providers.gradleProperty("deviceTestBuildType").orNull == "mvp"
val mvpSmokeSources = layout.buildDirectory.dir("generated/mvpSmokeSources")
val prepareMvpSmokeSources by tasks.registering(Sync::class) {
    from("src/androidTest/java") {
        include("com/example/autoedit/SyntheticVideo.kt",
            "com/example/autoedit/UiXmlRunListener.kt",
            "com/example/autoedit/TextEditActivityDeviceTest.kt")
    }
    into(mvpSmokeSources)
}
if (mvpDeviceTests) tasks.configureEach {
    if (name == "preMvpAndroidTestBuild") dependsOn(prepareMvpSmokeSources)
}

// Network access is build-time only. The application contains the verified model in assets.
val speechAssets = layout.buildDirectory.dir("generated/speechAssets")
val prepareSpeechAssets by tasks.registering {
    val revision = "5359861c739e955e79d9a303bcbc70fb988958b1"
    val checksum = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"
    inputs.property("revision", revision)
    inputs.property("checksum", checksum)
    outputs.file(speechAssets.map { it.file("speech/ggml-base.bin") })
    doLast {
        val cache = File(System.getProperty("user.home"), ".cache/veycad-speech").apply { mkdirs() }
        val model = File(cache, "ggml-base.bin")
        fun sha(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val bytes = ByteArray(128*1024)
                while (true) { val n=input.read(bytes); if(n<0) break; digest.update(bytes,0,n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (!model.isFile || sha(model) != checksum) {
            val partial = File(cache, "ggml-base.download")
            try {
                URI("https://huggingface.co/ggerganov/whisper.cpp/resolve/$revision/ggml-base.bin").toURL().openConnection().apply {
                    connectTimeout=30000; readTimeout=120000
                }.getInputStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
                check(sha(partial)==checksum) { "Speech model SHA-256 mismatch" }
                Files.move(partial.toPath(),model.toPath(),StandardCopyOption.REPLACE_EXISTING)
            } finally { partial.delete() }
        }
        val target = speechAssets.get().file("speech/ggml-base.bin").asFile
        target.parentFile.mkdirs(); model.copyTo(target, overwrite=true)
    }
}
tasks.configureEach {
    // Both lint model writers and analysis/report tasks inspect generated assets.
    if ((name.startsWith("merge") && name.endsWith("Assets")) ||
        name.contains("Lint") || name.startsWith("lint")) {
        dependsOn(prepareSpeechAssets)
    }
}

android {
    namespace = "com.veycad.app"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.veycad.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "0.1.16"
        buildConfigField("boolean", "GALLERY_MVP", "false")
        buildConfigField("String", "SOURCE_SHA", "\"$previewSourceSha\"")
        buildConfigField("boolean", "LIVE_PREVIEW_ENABLED", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild.cmake {
            arguments += listOf("-DFETCHCONTENT_BASE_DIR=" + File(System.getProperty("user.home"), ".cache/veycad-speech/cmake").path.replace('\\','/'))
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        create("manualMvp") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            buildConfigField("boolean", "LIVE_PREVIEW_ENABLED", "true")
            matchingFallbacks += "debug"
        }
        create("integrated") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".integrated2"
            versionNameSuffix = "-integrated"
            buildConfigField("boolean", "GALLERY_MVP", "true")
            matchingFallbacks += "debug"
        }
        create("musicEditor") {
            initWith(getByName("integrated"))
            applicationIdSuffix = ".musiceditor"
            versionNameSuffix = "-music-editor"
            resValue("string", "app_name", "Veykad · своя музыка")
        }
        create("mvp") {
            initWith(getByName("debug"))
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".mvp"
            versionNameSuffix = "-mvp"
            buildConfigField("boolean", "GALLERY_MVP", "true")
            matchingFallbacks += "debug"
        }
        create("cameraPilot") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".camera"
            versionNameSuffix = "-auto-camera-pilot"
            matchingFallbacks += "debug"
        }
        create("uiTest") {
            initWith(getByName("debug"))
            buildConfigField("boolean", "LIVE_PREVIEW_ENABLED", "true")
            applicationIdSuffix = if (providers.gradleProperty("textDeviceTest").orNull == "true") ".texttest" else ".uitest"
            matchingFallbacks += "debug"
        }
    }
    testBuildType = providers.gradleProperty("deviceTestBuildType").orNull ?: "uiTest"

    // Saved-MP4 diagnostics are opt-in and absent from the ordinary UI test APK/inventory.
    if (providers.gradleProperty("customMusicTargeted").orNull == "true") {
        sourceSets.getByName("androidTest").kotlin.directories += "src/customMusicDiagnostic/java"
    }
    // Remote diagnostic only: prebuilt JNI never enters an application source set.
    if (providers.gradleProperty("customMusicSoftwareAudioProbe").orNull == "true") {
        sourceSets.getByName("androidTest").kotlin.directories += "src/customMusicSoftwareDiagnostic/kotlin"
        // The remote runner populates this directory before Gradle; Android SourceSet accepts a File.
        sourceSets.getByName("androidTest").jniLibs.srcDir(layout.buildDirectory.dir("customMusicSoftwareDiagnostic/jniLibs").get().asFile)
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    externalNativeBuild.cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
        // Short isolated staging path also supports Windows hosts without long-path policy.
        buildStagingDirectory = File(System.getProperty("user.home"), ".cache/veycad-native/${rootDir.path.hashCode().toUInt()}")
    }
    androidResources { noCompress += "bin" }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/speechAssets").get().asFile)
    if (mvpDeviceTests) sourceSets.getByName("androidTest").java.setSrcDirs(
        listOf(mvpSmokeSources.get().asFile))

    lint {
        // This product currently has one Russian-localized UI. Keep correctness, permissions,
        // API-compatibility and security checks as hard gates; exclude only advisory style,
        // future-upgrade and single-locale copy checks that do not indicate a runtime defect.
        disable += setOf(
            "AndroidGradlePluginVersion", "ButtonStyle", "ContentDescription", "DefaultLocale",
            "DiscouragedApi", "DrawAllocation", "GradleDependency", "HardcodedText",
            "LockedOrientationActivity", "MonochromeLauncherIcon", "ObsoleteSdkInt", "OldTargetApi",
            "Overdraw", "RtlEnabled", "ScrollViewSize", "SetTextI18n", "SmallSp",
            "UnusedResources", "UseCompatLoadingForDrawables", "UseKtx"
        )
    }
}

androidComponents {
    if (providers.gradleProperty("customMusicSoftwareAudioProbe").orNull == "true") {
        onVariants(selector().withBuildType("uiTest")) { variant ->
            // Keep the reviewed binary byte-for-byte in the diagnostic test APK.
            checkNotNull(variant.androidTest) { "Software audio diagnostic requires the uiTest AndroidTest variant" }
                .packaging.jniLibs.keepDebugSymbols.add("**/libsoftware_aac_probe.so")
        }
    }
    beforeVariants(selector().all()) { variant ->
        (variant as com.android.build.api.variant.HasUnitTestBuilder).enableUnitTest =
            variant.buildType == "debug"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    val cameraVersion = "1.6.2"
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")
    implementation("androidx.camera:camera-video:$cameraVersion")
    implementation("androidx.camera:camera-view:$cameraVersion")
    // Bundled, on-device perception. No footage or biometric template leaves the phone.
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    // MediaPipe runs behind a non-exported provider in its own Android process. Keeping its
    // native runtime away from the ML Kit face/pose process prevents the observed JNI conflict.
    implementation("com.google.mediapipe:tasks-vision:1.0.0")
}

val verifySecurityContract by tasks.registering {
    group = "verification"
    description = "Verifies the release manifest and rejects credential-like literals in source files."
    dependsOn("processReleaseMainManifest")

    val sourceInputs = rootProject.fileTree(rootProject.projectDir) {
        include("**/*.kt", "**/*.kts", "**/*.java", "**/*.xml", "**/*.properties", "**/*.json")
        exclude(
            ".git/**", ".gradle/**", "**/build/**", "**/.idea/**",
            "TRANSFER-MANIFEST.json", "**/testFixtures/**"
        )
    }
    inputs.files(sourceInputs)

    doLast {
        val manifest = layout.buildDirectory.file(
            "intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml"
        ).get().asFile
        check(manifest.isFile) { "Release merged manifest was not generated: $manifest" }

        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val document = factory.newDocumentBuilder().parse(manifest)
        fun attributes(tag: String, attribute: String): List<String> {
            val nodes = document.getElementsByTagName(tag)
            return (0 until nodes.length).map { index ->
                nodes.item(index).attributes.getNamedItemNS(androidNamespace, attribute)?.nodeValue.orEmpty()
            }
        }

        val permissions = attributes("uses-permission", "name").toSet()
        val forbiddenPermissions = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.CHANGE_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_WIFI_STATE",
            "android.permission.NEARBY_WIFI_DEVICES"
        )
        check(permissions.intersect(forbiddenPermissions).isEmpty()) {
            "Release manifest contains forbidden network permissions: ${permissions.intersect(forbiddenPermissions)}"
        }

        val application = document.getElementsByTagName("application").item(0)
        check(application.attributes.getNamedItemNS(androidNamespace, "allowBackup")?.nodeValue == "false") {
            "Release application must set android:allowBackup=\"false\""
        }

        val providers = document.getElementsByTagName("provider")
        val providerExports = (0 until providers.length).associate { index ->
            val attributes = providers.item(index).attributes
            attributes.getNamedItemNS(androidNamespace, "name")?.nodeValue.orEmpty() to
                attributes.getNamedItemNS(androidNamespace, "exported")?.nodeValue.orEmpty()
        }
        val requiredPrivateProviders = setOf(
            "androidx.core.content.FileProvider",
            "com.veycad.app.MulticlassMatteProvider"
        )
        requiredPrivateProviders.forEach { provider ->
            check(providerExports[provider] == "false") {
                "Internal provider must exist and remain android:exported=\"false\": $provider"
            }
        }

        val releaseActivities = attributes("activity", "name").toSet()
        val debugManifest = file("src/debug/AndroidManifest.xml")
        val debugDocument = factory.newDocumentBuilder().parse(debugManifest)
        val debugNodes = debugDocument.getElementsByTagName("activity")
        val debugOnlyActivities = (0 until debugNodes.length).map { index ->
            debugNodes.item(index).attributes
                .getNamedItemNS(androidNamespace, "name")?.nodeValue.orEmpty()
        }.map { name ->
            when {
                name.startsWith(".") -> "com.veycad.app$name"
                "." !in name -> "com.veycad.app.$name"
                else -> name
            }
        }.toSet()
        check(releaseActivities.intersect(debugOnlyActivities).isEmpty()) {
            "Debug-only activities leaked into release: ${releaseActivities.intersect(debugOnlyActivities)}"
        }

        val sensitiveAssignment = Regex(
            """(?i)\b(?:api[_-]?key|client[_-]?secret|access[_-]?token|refresh[_-]?token|password)\b\s*(?:=|:)\s*[\"'][^\"'\$\r\n]{8,}[\"']"""
        )
        val jwtLiteral = Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b""")
        val knownSecretLiteral = Regex(
            """(?:AIza[0-9A-Za-z_-]{30,}|gh[pousr]_[0-9A-Za-z]{20,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)"""
        )
        val findings = mutableListOf<String>()
        sourceInputs.files.sortedBy { it.path }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (sensitiveAssignment.containsMatchIn(line) || jwtLiteral.containsMatchIn(line) ||
                    knownSecretLiteral.containsMatchIn(line)) {
                    findings += "${file.relativeTo(rootProject.projectDir)}:${index + 1}"
                }
            }
        }
        check(findings.isEmpty()) {
            "Credential-like literal found in source files:\n${findings.joinToString("\n")}"
        }
    }
}

tasks.named("check") {
    dependsOn(verifySecurityContract)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    failOnNoDiscoveredTests.set(true)
    afterSuite(org.gradle.kotlin.dsl.KotlinClosure2<
        org.gradle.api.tasks.testing.TestDescriptor,
        org.gradle.api.tasks.testing.TestResult, Unit>({ suite, result ->
        if (suite.parent == null) {
            check(result.testCount > 0L) { "No JVM tests executed" }
            check(result.skippedTestCount == 0L) { "Skipped JVM tests do not satisfy the complete suite" }
        }
    }))
}
