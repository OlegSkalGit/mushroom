package com.olegskal.mushroom.mushrooms

data class DataFileItem(
    val fileName: String,
    val exactSize: Long
)

object MushroomDataConfig {

    // =========================================================================
    // 1. ОФЛАЙН-БАЗА ДАНИХ (Багатотомний архів та розпакована SQLite база)
    // =========================================================================
    const val DB_FILE_NAME = "mushrooms.db"
    const val DB_DIR_NAME = "mushrooms"
    const val DB_MIN_SIZE = 400 * 1024 * 1024L
    const val DB_FULL_SIZE = 560164864L // Розмір розпакованого mushrooms.db

    const val DB_BASE_DOWNLOAD_URL = "https://raw.githubusercontent.com/OlegSkalGit/mushroom/main/downloads/"

    val DB_PARTS = listOf(
        DataFileItem("mushrooms.z01", 69206016L),
        DataFileItem("mushrooms.z02", 69206016L),
        DataFileItem("mushrooms.z03", 69206016L),
        DataFileItem("mushrooms.z04", 69206016L),
        DataFileItem("mushrooms.z05", 69206016L),
        DataFileItem("mushrooms.z06", 69206016L),
        DataFileItem("mushrooms.z07", 69206016L),
        DataFileItem("mushrooms.zip", 64713165L)
    )
    val DB_TOTAL_ARCHIVE_SIZE = DB_PARTS.sumOf { it.exactSize }

    // =========================================================================
    // 2. НЕЙРОМЕРЕЖА / МОДЕЛЬ (Архів model.zip та розпаковані компоненти)
    // =========================================================================
    const val MODEL_DIR_NAME = "model"
    const val MODEL_ZIP_NAME = "model.zip"
    const val MODEL_ZIP_FULL_SIZE = 77472751L // Розмір архіву model.zip

    const val MODEL_ZIP_PRIMARY_URL = "https://raw.githubusercontent.com/OlegSkalGit/mushroom/main/downloads/model.zip"
    const val MODEL_ZIP_FALLBACK_URL = "https://github.com/OlegSkalGit/mushroom/raw/main/downloads/model.zip"

    val MODEL_REQUIRED_FILES = listOf(
        DataFileItem("model2.onnx", 282042454L),
        DataFileItem("classes.json", 204272L),
        DataFileItem("ort-wasm-simd.wasm", 10549605L),
        DataFileItem("ort.min.js", 540029L)
    )
}
