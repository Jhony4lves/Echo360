package com.jhony4lves.echo360.domain.xbox

object XboxDevicePath {
    private val canonicalToDevice = linkedMapOf(
        "Hdd1" to "\\Device\\Harddisk0\\Partition1",
        "Hdd0" to "\\Device\\Harddisk0\\Partition0",
        "Hddx" to "\\Device\\Harddisk0\\SystemPartition",
        "Usb0" to "\\Device\\Mass0",
        "Usb1" to "\\Device\\Mass1",
        "Usb2" to "\\Device\\Mass2",
        "Flash" to "\\SystemRoot",
    )

    fun toDevicePath(canonicalInput: String): String {
        val canonical = XboxPath.canonical(canonicalInput)
        require(canonical != "/") { "A raiz FTP não pode ser convertida em caminho de execução." }
        val segments = canonical.removePrefix("/").split('/').filter(String::isNotBlank)
        val drive = segments.first()
        val deviceRoot = canonicalToDevice.entries.firstOrNull {
            it.key.equals(drive, ignoreCase = true)
        }?.value ?: error("Drive $drive ainda não possui mapeamento NOVA.")
        val rest = segments.drop(1).joinToString("\\")
        return if (rest.isBlank()) deviceRoot else "$deviceRoot\\$rest"
    }

    fun fromDevicePath(deviceInput: String): String? {
        val normalized = deviceInput.trim().replace('/', '\\')
        val match = canonicalToDevice.entries.firstOrNull { (_, deviceRoot) ->
            normalized.equals(deviceRoot, ignoreCase = true) ||
                normalized.startsWith("$deviceRoot\\", ignoreCase = true)
        } ?: return null

        val rest = normalized.removePrefixIgnoreCase(match.value).trimStart('\\')
        return XboxPath.canonical(
            if (rest.isBlank()) "/${match.key}" else "/${match.key}/${rest.replace('\\', '/')}",
        )
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this
}
