package app.hypochlorite.netease

data class QualityPreset(
    val id: String,
    val label: String,
    val fetchBr: Int,
    val fetchLevel: String,
)

object Quality {
    val PRESETS = listOf(
        QualityPreset("master", "超清母带", 999000, "jymaster"),
        QualityPreset("hires", "Hi-Res", 999000, "hires"),
        QualityPreset("lossless", "无损", 999000, "lossless"),
        QualityPreset("high", "高", 320000, "exhigh"),
        QualityPreset("standard", "标准", 192000, "higher"),
        QualityPreset("saver", "省流", 128000, "standard"),
    )

    private val aliases = mapOf(
        "jymaster" to "master",
        "jm" to "master",
        "sq" to "lossless",
        "exhigh" to "high",
        "hq" to "high",
        "higher" to "standard",
    )

    fun resolve(id: String?): QualityPreset {
        val key = (id ?: "").trim().lowercase()
        val mapped = aliases[key] ?: key
        return PRESETS.firstOrNull { it.id == mapped } ?: PRESETS.first { it.id == "high" }
    }
}
