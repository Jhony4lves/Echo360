package com.jhony4lves.echo360.domain.library

data class GameEntry(
    val databaseId: Long,
    val titleId: Long,
    val mediaId: Long,
    val discNumber: Int,
    val title: String,
    val directory: String,
    val executable: String,
    val baseVersion: String?,
    val contentRoot: String?,
) {
    val titleIdHex: String get() = titleId.toUInt().toString(16).uppercase().padStart(8, '0')
    val mediaIdHex: String get() = mediaId.toUInt().toString(16).uppercase().padStart(8, '0')

    val canonicalDirectory: String?
        get() {
            val root = contentRoot?.trim()?.replace('\\', '/')?.trimEnd('/')
            val child = directory.trim().replace('\\', '/').trim('/')
            if (root.isNullOrBlank()) return null
            return if (child.isBlank()) root else "$root/$child"
        }

    val canonicalExecutablePath: String?
        get() = canonicalDirectory?.trimEnd('/')?.let { "$it/$executable" }
}

data class NowPlaying(
    val titleId: Long,
    val mediaId: Long,
    val executableDevicePath: String,
    val titleUpdateVersion: Int,
    val discCurrent: Int,
    val discCount: Int,
    val resolutionWidth: Int,
    val resolutionHeight: Int,
    val baseVersion: String?,
    val currentVersion: String?,
) {
    val titleIdHex: String get() = titleId.toUInt().toString(16).uppercase().padStart(8, '0')
    val mediaIdHex: String get() = mediaId.toUInt().toString(16).uppercase().padStart(8, '0')
}

data class LibrarySnapshot(
    val games: List<GameEntry>,
    val auroraRoot: String,
    val databaseRemotePath: String,
    val databaseBytes: Long,
)
