package com.jhony4lves.echo360.domain.xbox

object XboxPath {
    private val ftpDllDriveMap = mapOf(
        "Hdd1" to "fHdd",
        "Usb0" to "fUsb0",
        "Flash" to "fFlash",
    )

    fun canonical(input: String): String {
        var value = input.trim().replace('\\', '/')
        if (value.isBlank() || value == "/") return "/"

        value = value.replace(Regex("/+"), "/")
        if (!value.startsWith('/')) value = "/$value"

        val segments = value
            .split('/')
            .filter { it.isNotBlank() }
            .toMutableList()

        if (segments.isEmpty()) return "/"
        segments[0] = segments[0].removeSuffix(":")

        return "/" + segments.joinToString("/")
    }

    fun toAuroraFtpPath(input: String): String = canonical(input)

    fun toFtpDllPath(input: String): String {
        val canonical = canonical(input)
        if (canonical == "/") return "/"

        val segments = canonical.removePrefix("/").split('/').toMutableList()
        val drive = ftpDllDriveMap.entries.firstOrNull {
            it.key.equals(segments.first(), ignoreCase = true)
        } ?: throw IllegalArgumentException(
            "Drive ${segments.first()} não está disponível no namespace do FTPdll."
        )

        segments[0] = drive.value
        return "/" + segments.joinToString("/")
    }

    fun fromFtpDllPath(input: String): String {
        val normalized = canonical(input)
        if (normalized == "/") return "/"

        val reverseMap = ftpDllDriveMap.entries.associate { it.value to it.key }
        val segments = normalized.removePrefix("/").split('/').toMutableList()
        val drive = reverseMap.entries.firstOrNull {
            it.key.equals(segments.first(), ignoreCase = true)
        } ?: throw IllegalArgumentException(
            "Drive ${segments.first()} não pertence ao namespace conhecido do FTPdll."
        )

        segments[0] = drive.value
        return "/" + segments.joinToString("/")
    }
}
