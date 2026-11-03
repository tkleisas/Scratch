import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
        "-DCMAKE_BUILD_TYPE=Release", "-DJAVA_INCLUDE_PATH=$include",
    )
}

val buildDesktopNative = tasks.register<Exec>("buildDesktopNative") {
    group = "native"
    dependsOn(configureDesktopNative)
    commandLine("cmake", "--build", nativeBuildDir.absolutePath, "-j")
}

val bundledNativeDir = layout.buildDirectory.dir("generated/native-resources").get().asFile
val copyNativeLibrary = tasks.register<Copy>("copyNativeLibrary") {
    group = "native"
    dependsOn(buildDesktopNative)
    from(nativeBuildDir.resolve("libscratchdj.so"))
    into(File(bundledNativeDir, "native/linux-x64"))
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
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage,
            )
            packageName = "Scratch"
            packageVersion = project.version.toString()
                .substringBefore('+')
                .substringBefore('-')
            description = "Vinyl-first DJ app for Linux"
            vendor = "Tasos Kleisas"
            modules("java.prefs")
            linux {
                iconFile.set(project.file("src/main/resources/scratch.png"))
            }
        }
    }
}

tasks.matching { it.name == "run" && it is JavaExec }.configureEach {
    dependsOn(buildDesktopNative)
    (this as JavaExec).systemProperty("java.library.path", nativeBuildDir.absolutePath)
}
