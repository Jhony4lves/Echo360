package com.jhony4lves.echo360.data.transfer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.jhony4lves.echo360.domain.transfer.LocalTransferFile
import com.jhony4lves.echo360.domain.transfer.LocalTransferTree
import com.jhony4lves.echo360.domain.transfer.normalizeRelativePath
import com.jhony4lves.echo360.domain.transfer.relativeKey
import com.jhony4lves.echo360.domain.xbox.XboxFatx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalDocumentTreeScanner(
    private val context: Context,
) {
    suspend fun scan(treeUri: Uri): LocalTransferTree = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Não foi possível abrir a pasta selecionada.")
        require(root.exists() && root.isDirectory) { "A origem selecionada não é uma pasta válida." }

        val files = mutableListOf<LocalTransferFile>()
        val directories = linkedSetOf("")
        val reservedTargets = linkedMapOf<String, String>()

        fun reserveTarget(targetRelativePath: String, sourceName: String) {
            val key = relativeKey(targetRelativePath)
            val existing = reservedTargets.putIfAbsent(key, sourceName)
            require(existing == null) {
                "Conflito FATX: '$existing' e '$sourceName' geram o mesmo destino '$targetRelativePath'. Renomeie um deles no Android."
            }
        }

        fun walk(directory: DocumentFile, relativeDirectory: String) {
            val children = directory.listFiles()
                .sortedWith(compareBy<DocumentFile>({ !it.isDirectory }, { it.name?.lowercase().orEmpty() }))

            for (child in children) {
                val sourceName = child.name?.trim().orEmpty()
                if (sourceName.isBlank()) continue

                val targetName = XboxFatx.safeSegment(sourceName)
                val relative = normalizeRelativePath(
                    listOf(relativeDirectory, targetName)
                        .filter(String::isNotBlank)
                        .joinToString("/"),
                )
                reserveTarget(relative, sourceName)

                when {
                    child.isDirectory -> {
                        directories += relative
                        walk(child, relative)
                    }

                    child.isFile -> files += LocalTransferFile(
                        relativePath = relative,
                        size = child.length().coerceAtLeast(0L),
                        contentUri = child.uri.toString(),
                    )
                }
            }
        }

        walk(root, "")

        LocalTransferTree(
            rootUri = treeUri.toString(),
            rootName = root.name ?: "Pasta Android",
            files = files,
            directories = directories,
        )
    }
}
