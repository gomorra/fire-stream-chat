package com.firestream.chat.domain.model

/**
 * Available release published by CI to GitHub Releases. Mirrors the
 * `latest-{flavor}.json` manifest schema documented in `docs/RELEASING.md`.
 */
data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val minSupportedVersionCode: Int,
    val releaseNotes: String,
    val publishedAt: String,
    val mandatory: Boolean
)

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class Available(val update: AppUpdate) : UpdateCheckResult
}
