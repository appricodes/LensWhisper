plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.1.2"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.appricodes.lenswhisper.desktop.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

// Builds a native, double-clickable app image via jpackage — a folder containing a launcher exe
// (Windows)/app bundle (macOS)/binary (Linux) plus a bundled JRE, so end users don't need Java
// installed at all. Reuses `installDist`'s output (main jar + every dependency jar side by side
// in one folder) as jpackage's --input: a non-modular multi-jar app just needs all its jars in
// the same directory, no fat/shadow jar required. See desktop/README.md's "Building a standalone
// distribution" section for how to run this and where the output lands.
//
// --type app-image (not --type exe/msi) deliberately: those installer types need the WiX Toolset
// on the build machine, which isn't a project dependency today. app-image already produces a
// real, runnable executable — an actual installer can be layered on later without changing
// anything here, by adding --type exe once WiX is available.
tasks.register<Exec>("jpackageImage") {
    group = "distribution"
    description = "Builds a native app image (a runnable folder with its own launcher executable and bundled JRE — no separate Java install needed) via jpackage."
    dependsOn("installDist")

    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }.get()
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val jpackageExe = launcher.executablePath.asFile.resolveSibling(if (isWindows) "jpackage.exe" else "jpackage")
    val inputDir = layout.buildDirectory.dir("install/${rootProject.name}/lib")
    val destDir = layout.buildDirectory.dir("jpackage")

    doFirst {
        check(jpackageExe.exists()) {
            "jpackage not found at $jpackageExe — need a JDK 17+ (jpackage ships with the JDK, not just a JRE)."
        }
        destDir.get().asFile.deleteRecursively()
    }

    commandLine(
        jpackageExe.absolutePath,
        "--type", "app-image",
        "--input", inputDir.get().asFile.absolutePath,
        "--main-jar", "${rootProject.name}.jar",
        "--main-class", "com.appricodes.lenswhisper.desktop.MainKt",
        "--name", "LensWhisper Desktop",
        "--app-version", "1.0.0",
        "--vendor", "AppriCodes",
        "--dest", destDir.get().asFile.absolutePath,
    )
}
