package com.droiddeck.launcher.core

object GameEnvironment {
    data class Config(
        val shared: Map<String, String?> = emptyMap(),
        val games: Map<String, Map<String, String?>> = emptyMap(),
    ) {
        fun entries(scope: String) = if (scope.isEmpty()) shared else games[scope].orEmpty()

        fun withEntries(scope: String, entries: Map<String, String?>): Config =
            if (scope.isEmpty()) copy(shared = entries.toMap())
            else copy(games = if (entries.isEmpty()) games - scope else games + (scope to entries.toMap()))
    }

    private val namePattern = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val unused = setOf(
        "WINEESYNC", "WINENTSYNC", "WINE_FAST_YIELD", "WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER",
        "WINE_NEW_MEDIASOURCE", "WRAPPER_MAX_IMAGE_COUNT", "WRAPPER_DMAHEAP_CACHED",
        "ALSA_LATENCY_MS", "ALSA_VOLUME", "ALSA_BASS_BOOST", "ALSA_PERFORMANCE_MODE",
        "DXVK_ASYNC", "DXVK_GPLASYNCCACHE",
    )

    fun validName(name: String) = namePattern.matches(name)
    fun supported(name: String) = name !in unused && !name.startsWith("BOX64_") && !name.startsWith("BOX86_")
    fun validValue(value: String) = '\u0000' !in value && value.length <= 8192
    fun validScope(scope: String) = scope.isEmpty() || (Regex("[1-9][0-9]*").matches(scope) && scope.toLongOrNull()?.let { it in 1..4294967295L } == true)

    /** Applied at launch only where the game's inherited environment leaves a variable unset. */
    fun defaults(preset: String): Map<String, String> = linkedMapOf(
        "MESA_SHADER_CACHE_DISABLE" to "false",
        "TU_DEBUG" to "sysmem",
        "VKD3D_FEATURE_LEVEL" to "12_2",
        "VKD3D_SHADER_MODEL" to "6_6",
    ).apply {
        FexPreset.env(preset).forEach { put(it.substringBefore('='), it.substringAfter('=')) }
    }

    enum class Origin { DEFAULT, SHARED, PROFILE }
    data class Entry(val name: String, val value: String?, val origin: Origin)

    /** What a profile's game starts with, in name order, each entry tagged with where it comes from. */
    fun resolve(config: Config, preset: String, scope: String): List<Entry> {
        val entries = LinkedHashMap<String, Entry>()
        defaults(preset).forEach { (name, value) -> entries[name] = Entry(name, value, Origin.DEFAULT) }
        if (scope.isNotEmpty()) config.shared.forEach { (name, value) -> entries[name] = Entry(name, value, Origin.SHARED) }
        config.entries(scope).forEach { (name, value) -> entries[name] = Entry(name, value, Origin.PROFILE) }
        return entries.values.sortedWith(compareBy({ it.name.uppercase(java.util.Locale.ROOT) }, { it.name }))
    }
}
