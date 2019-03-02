package tylerwalker.io.kanjireader

data class Kanji(
        val character: String,
        val id: Int,
        val strokeCount: Int,
        val grade: String,
        val radical: String,
        val onReading: String,
        val kunReading: String,
        val nanoriReading: String,
        val onRomajiReading: String,
        val kunRomajiReading: String,
        val meaning: String,
        val label: Int
) {
    override fun toString(): String =
            "Kanji {" +
                    "character: $character," +
                    "label: $label," +
                    "on: $onReading, " +
                    "kun: $kunReading, " +
                    "meaning: $meaning" +
                    "}"
}