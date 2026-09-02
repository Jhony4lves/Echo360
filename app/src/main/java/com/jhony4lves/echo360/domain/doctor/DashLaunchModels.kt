package com.jhony4lves.echo360.domain.doctor

data class DashLaunchOption(
    val id: Long,
    val category: String,
    val name: String,
    val value: String,
)

data class DashLaunchVersion(
    val kernel: Long,
    val major: Long,
    val minor: Long,
    val build: Long,
) {
    val display: String
        get() = "$major.$minor.$build"
}

data class DashLaunchSnapshot(
    val options: List<DashLaunchOption>,
    val version: DashLaunchVersion,
) {
    fun option(name: String): DashLaunchOption? = options.firstOrNull {
        it.name.equals(name, ignoreCase = true)
    }

    fun optionValue(name: String): String? = option(name)?.value

    val plugins: List<DashLaunchPlugin>
        get() = options
            .filter { it.category.equals("Plugins", ignoreCase = true) }
            .filter { it.name.matches(Regex("plugin[1-5]", RegexOption.IGNORE_CASE)) }
            .sortedBy { it.name.lowercase() }
            .map { option ->
                DashLaunchPlugin(
                    slot = option.name.filter(Char::isDigit).toIntOrNull() ?: 0,
                    path = option.value.trim(),
                )
            }
}

data class DashLaunchPlugin(
    val slot: Int,
    val path: String,
) {
    val configured: Boolean
        get() = path.isNotBlank()
}
