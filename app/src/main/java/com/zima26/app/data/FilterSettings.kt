package com.zima26.app.data

/**
 * Intensidad del desgaste. El factor escala el grano, los rayones, el polvo y las manchas.
 */
enum class WearLevel(val label: String, val factor: Float) {
    SUAVE("Suave", 0.55f),
    NORMAL("Normal", 1.0f),
    MUY_VIEJO("Muy viejo", 1.7f)
}

/**
 * Estilo de época de la copia. Los tres últimos conservan algo de color
 * ([usesColor]); el resto son monocromos virados.
 */
enum class PhotoTone(val label: String, val usesColor: Boolean = false) {
    SEPIA("Sepia 1943"),
    BLANCO_NEGRO("Blanco y negro"),
    DAGUERROTIPO("Daguerrotipo"),
    CIANOTIPIA("Cianotipia"),
    POLAROID("Polaroid 70s", true),
    KODACHROME("Kodachrome 60s", true),
    POSTAL("Postal coloreada", true)
}

/** Modo del flash de la cámara. */
enum class FlashSetting(val label: String) {
    AUTO("Auto"),
    ON("Sí"),
    OFF("No")
}

/**
 * Ajustes del usuario que afectan al filtro y al guardado.
 */
data class FilterSettings(
    val borderText: String = DEFAULT_TEXT,
    val showText: Boolean = true,
    /** Escribir también la fecha actual con la letra manuscrita. */
    val showDate: Boolean = false,
    val wear: WearLevel = WearLevel.NORMAL,
    val tone: PhotoTone = PhotoTone.SEPIA,
    val showBorder: Boolean = true,
    val saveOriginal: Boolean = false,
    // Desgaste físico
    val folds: Boolean = false,
    val stains: Boolean = false,
    val dogEar: Boolean = false,
    val tape: Boolean = false
) {
    companion object {
        const val DEFAULT_TEXT = "1943"
    }
}
