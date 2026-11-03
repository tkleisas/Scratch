import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

version = rootProject.extra["gitVersionName"] as String

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val appInfoVersion = project.version.toString()

val generateAppInfo = tasks.register("generateAppInfo") {
    val outputDir = layout.buildDirectory.dir("generated/appinfo").get().asFile
    inputs.property("version", appInfoVersion)
    outputs.dir(outputDir)
    doLast {
        val file = File(outputDir, "com/tkleisas/scratchdj/desktop/AppInfo.kt")
        file.parentFile.mkdirs()
        file.writeText(
            "package com.tkleisas.scratchdj.desktop\n\n" +
                "object AppInfo {\n" +
                "    const val VERSION = \"$appInfoVersion\"\n" +
                "}\n",
        )
    }
}

kotlin.sourceSets["main"].kotlin.srcDir(generateAppInfo)

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    testImplementation("junit:junit:4.13.2")
}

val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val enableAsio = (providers.gradleProperty("scratch.asio").orNull
    ?: System.getenv("SCRATCH_ASIO"))
    ?.toBoolean() == true
val nativeLibraryName = if (isWindows) "scratchdj.dll" else "libscratchdj.so"
val nativeResourceDir = if (isWindows) "windows-x64" else "linux-x64"

val nativeBuildDir = layout.buildDirectory.dir("native").get().asFile
val jniInclude = providers.gradleProperty("jniInclude").orNull
    ?: System.getenv("JAVA_INCLUDE_PATH")
    ?: System.getenv("JAVA_HOME")?.let { "$it/include" }

val configureDesktopNative = tasks.register<Exec>("configureDesktopNative") {
    group = "native"
    workingDir = projectDir
    val include = jniInclude
        ?: throw GradleException(
            "Set JAVA_INCLUDE_PATH or pass -PjniInclude=<full JDK>/include for jni.h",
        )
    commandLine(
        "cmake", "-S", "native", "-B", nativeBuildDir.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DJAVA_INCLUDE_PATH=$include",
        "-DSCRATCH_ENABLE_ASIO=${if (enableAsio) "ON" else "OFF"}",
    )
}

val buildDesktopNative = tasks.register<Exec>("buildDesktopNative") {
    group = "native"
    dependsOn(configureDesktopNative)
    commandLine(
        "cmake", "--build", nativeBuildDir.absolutePath,
        "--config", "Release", "--parallel",
    )
}

val bundledNativeDir = layout.buildDirectory.dir("generated/native-resources").get().asFile
val copyNativeLibrary = tasks.register<Copy>("copyNativeLibrary") {
    group = "native"
    dependsOn(buildDesktopNative)
    from(nativeBuildDir) {
        include("**/$nativeLibraryName")
        eachFile { path = name }
    }
    into(File(bundledNativeDir, "native/$nativeResourceDir"))
}

sourceSets {
    main {
        resources.srcDir(bundledNativeDir)
    }
}

tasks.named("processResources") {
    dependsOn(copyNativeLibrary)
}

compose.desktop {
    application {
        mainClass = "com.tkleisas.scratchdj.desktop.MainKt"

        nativeDistributions {
            if (isWindows) {
                targetFormats(
                    org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                    org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
                )
            } else {
                targetFormats(
                    org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                    org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage,
                )
            }
            packageName = "Scratch"
            packageVersion = project.version.toString()
                .substringBefore('+')
                .substringBefore('-')
            description = if (isWindows) {
                "Vinyl-first DJ app for Windows"
            } else {
                "Vinyl-first DJ app for Linux"
            }
            vendor = "Tasos Kleisas"
            modules("java.prefs")
            if (isWindows) {
                windows {
                    iconFile.set(project.file("src/main/resources/scratch.ico"))
                }
            } else {
                linux {
                    iconFile.set(project.file("src/main/resources/scratch.png"))
                }
            }
        }
    }
}

tasks.matching { it.name == "run" && it is JavaExec }.configureEach {
    dependsOn(buildDesktopNative)
    val searchPath = listOf(
        nativeBuildDir,
        File(nativeBuildDir, "Release"),
    ).joinToString(File.pathSeparator)
    (this as JavaExec).systemProperty("java.library.path", searchPath)
}
