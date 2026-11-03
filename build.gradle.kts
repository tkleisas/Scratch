plugins {
    id("com.android.application") version "8.10.1" apply false
    id("com.android.library") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.3.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
}


fun gitOutput(vararg arguments: String): String? = try {
    val process = ProcessBuilder(listOf("git", *arguments))
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    if (process.waitFor() == 0 && output.isNotEmpty()) output else null
} catch (_: Exception) {
    null
}

fun parseSemver(tag: String): Triple<Int, Int, Int>? {
    val clean = tag.removePrefix("v").substringBefore("-").substringBefore("+")
    val parts = clean.split(".")
    if (parts.size != 3) return null
    val major = parts[0].toIntOrNull() ?: return null
    val minor = parts[1].toIntOrNull() ?: return null
    val patch = parts[2].toIntOrNull() ?: return null
    return Triple(major, minor, patch)
}

val gitDescribe = gitOutput("describe", "--tags", "--long", "--dirty")
val totalCommits = gitOutput("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 0

var gitVersionName = "0.0.0+$totalCommits"
var gitVersionCode = totalCommits.coerceAtLeast(1)

if (gitDescribe != null) {
    val dirty = gitDescribe.endsWith("-dirty")
    val cleanDescribe = gitDescribe.removeSuffix("-dirty")
    val match = Regex("^(.+)-(\\d+)-g([0-9a-fA-F]+)$").find(cleanDescribe)
    if (match != null) {
        val tag = match.groupValues[1]
        val commitsSinceTag = match.groupValues[2].toIntOrNull() ?: 0
        val hash = match.groupValues[3]
        val semver = parseSemver(tag)
        if (semver != null) {
            val base = "${semver.first}.${semver.second}.${semver.third}"
            val metadata = buildList {
                if (commitsSinceTag > 0) add("$commitsSinceTag.g$hash")
                if (dirty) add("dirty")
            }
            gitVersionName = if (metadata.isEmpty()) base else "$base+${metadata.joinToString(".")}"
            gitVersionCode = semver.first * 10_000_000 +
                semver.second * 100_000 +
                semver.third * 1_000 +
                commitsSinceTag.coerceAtMost(999)
        }
    }
}

extra["gitVersionName"] = gitVersionName
extra["gitVersionCode"] = gitVersionCode
