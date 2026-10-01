plugins {
    id("com.android.application") version "9.1.1" apply false
}

val pythonExecutable = providers.gradleProperty("pythonExecutable")
    .orElse(providers.environmentVariable("PYTHON"))
    .orElse(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3")

val pythonTests by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs every tools/test_*.py test and fails on errors, empty discovery or skips."
    workingDir(rootDir)
    commandLine(pythonExecutable.get(), "tools/run_tests.py", "--report", "build/reports/python-tests.json")
}

tasks.register<Exec>("testMutationCheck") {
    group = "verification"
    description = "Checks selected JVM regressions in memory without editing application files."
    dependsOn(":app:compileDebugUnitTestKotlin")
    doFirst {
        val tests = project(":app").tasks.named<Test>("testDebugUnitTest").get()
        val java = java.io.File(System.getProperty("java.home"), "bin/java").absolutePath
        commandLine(java, "--class-path", tests.classpath.asPath,
            rootProject.file("tools/TestMutationCheck.java").absolutePath,
            rootProject.file("build/reports/test-mutations.json").absolutePath)
    }
}

tasks.register<Exec>("pythonMutationCheck") {
    group = "verification"
    description = "Checks selected Python regressions in memory without editing tools."
    workingDir(rootDir)
    commandLine(pythonExecutable.get(), "tools/check_python_test_mutations.py", "--report",
        "build/reports/python-test-mutations.json")
}

tasks.register("baselineVerify") {
    group = "verification"
    description = "Runs Android and Python tests, lint, release security contract and builds the debug APK."
    dependsOn(
        pythonTests,
        ":app:testDebugUnitTest",
        ":app:lintDebug",
        ":app:verifySecurityContract",
        ":app:assembleDebug"
    )
    doLast {
        check(!project(":app").tasks.named("testDebugUnitTest").get().state.noSource) {
            "The Android test suite has no sources"
        }
    }
}
