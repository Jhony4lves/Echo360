package com.jhony4lves.echo360.data.library

import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.domain.xbox.XboxPath

internal object AuroraArtworkPaths {
    fun gameDataDirectory(auroraRoot: String, game: GameEntry): String =
        "${XboxPath.canonical(auroraRoot).trimEnd('/')}/Data/GameData/${game.titleIdHex}_${contentIdHex(game.databaseId)}"

    fun coverAsset(auroraRoot: String, game: GameEntry): String =
        "${gameDataDirectory(auroraRoot, game)}/GC${game.titleIdHex}.asset"

    fun backgroundAsset(auroraRoot: String, game: GameEntry): String =
        "${gameDataDirectory(auroraRoot, game)}/BK${game.titleIdHex}.asset"

    fun contentIdHex(databaseId: Long): String =
        (databaseId and 0xFFFF_FFFFL).toString(16).uppercase().padStart(8, '0')

    fun cacheStem(game: GameEntry): String =
        "${game.titleIdHex}_${contentIdHex(game.databaseId)}"
}
