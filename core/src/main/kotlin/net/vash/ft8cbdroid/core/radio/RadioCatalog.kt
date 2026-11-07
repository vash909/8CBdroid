package net.vash.ft8cbdroid.core.radio

/** Protocol metadata, never evidence that a physical radio/USB setup works. */
data class RadioModel(
    val id: Int,
    val manufacturer: String,
    val name: String,
    val driverStatus: String,
    val portType: Int,
    val baudMin: Int,
    val baudMax: Int,
    val readsFrequency: Boolean,
    val readsMode: Boolean,
    val readsPtt: Boolean,
) {
    init { require(id > 0); require(manufacturer.isNotBlank()); require(name.isNotBlank()) }
    val isDummy: Boolean get() = id == 1
    fun matches(query: String): Boolean = query.trim().let {
        it.isEmpty() || id.toString() == it || manufacturer.contains(it, ignoreCase = true) || name.contains(it, ignoreCase = true)
    }
}

data class RadioCatalogSnapshot(val version: String, val models: List<RadioModel>) {
    init {
        require(version.isNotBlank())
        require(models.isNotEmpty() && models.size <= 4096)
        require(models.map { it.id }.distinct().size == models.size)
    }
}

/** Fixed software-only probe: implementations must not accept a physical model ID. */
data class DummyRadioCheck(val frequencyHz: Long, val mode: String, val pttOff: Boolean) {
    val passed: Boolean get() = frequencyHz == 27_265_000L && mode == "USB" && pttOff
}

interface RadioCatalog {
    suspend fun load(): RadioCatalogSnapshot
    suspend fun checkDummy(): DummyRadioCheck
}
