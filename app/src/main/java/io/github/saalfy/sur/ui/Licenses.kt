package io.github.saalfy.sur.ui

/**
 * One third-party library used by Sur, with its license.
 *
 * Licenses were checked against each project's own repository. "License: see project page"
 * means no license was found there.
 */
data class OpenSourceLicense(
    val name: String,
    val license: String,
    val description: String,
)

/** Third-party libraries used by Sur, in the order shown in the About dialog. */
val openSourceLicenses: List<OpenSourceLicense> = listOf(
    OpenSourceLicense(
        name = "AndroidX libraries",
        license = "Apache-2.0",
        description = "Core, Activity, Lifecycle, Room, Palette and Compose (BOM, UI, Material 3).",
    ),
    OpenSourceLicense(
        name = "Media3",
        license = "Apache-2.0",
        description = "ExoPlayer and MediaSession for audio playback.",
    ),
    OpenSourceLicense(
        name = "OkHttp",
        license = "Apache-2.0",
        description = "HTTP client for downloads and network lookups.",
    ),
    OpenSourceLicense(
        name = "Kotlin coroutines",
        license = "Apache-2.0",
        description = "Asynchronous programming for Kotlin.",
    ),
    OpenSourceLicense(
        name = "NewPipeExtractor",
        license = "GPL-3.0",
        description = "Extracts audio stream info from YouTube links.",
    ),
    OpenSourceLicense(
        name = "nanojson",
        license = "License: see project page",
        description = "Tiny JSON parser used by NewPipeExtractor.",
    ),
    OpenSourceLicense(
        name = "Reorderable",
        license = "Apache-2.0",
        description = "Drag to reorder for playlist rows.",
    ),
    OpenSourceLicense(
        name = "desugar_jdk_libs",
        license = "GPL-2.0",
        description = "Java 8+ language features on old Android, with Classpath exception.",
    ),
)
