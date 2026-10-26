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
    const val DB_MIN_SIZE = 100 * 1024 * 1024L
    const val DB_FULL_SIZE = 115671040L // Розмір розпакованого mushrooms.db

    const val DB_BASE_DOWNLOAD_URL = "https://raw.githubusercontent.com/OlegSkalGit/mushroom/main/downloads/"

    val DB_PARTS = listOf(
        DataFileItem("mushrooms.z01", 52428800L),
        DataFileItem("mushrooms.zip", 51888778L)
    )
    val DB_TOTAL_ARCHIVE_SIZE = DB_PARTS.sumOf { it.exactSize }

    // =========================================================================
    // 2. НЕЙРОМЕРЕЖА / МОДЕЛЬ (Багатотомний архів та розпаковані компоненти)
    // =========================================================================
    const val MODEL_DIR_NAME = "model"
    const val MODEL_BASE_DOWNLOAD_URL = "https://raw.githubusercontent.com/OlegSkalGit/mushroom/main/downloads/"
    const val MODEL_FALLBACK_DOWNLOAD_URL = "https://github.com/OlegSkalGit/mushroom/raw/main/downloads/"

    val MODEL_PARTS = listOf(
        DataFileItem("model.z01", 62914560L),
        DataFileItem("model.zip", 62508053L)
    )
    val MODEL_TOTAL_ARCHIVE_SIZE = MODEL_PARTS.sumOf { it.exactSize }

    val MODEL_REQUIRED_FILES = listOf(
        DataFileItem("beit_base_384_arm_int8.ort", 123692528L),
        DataFileItem("swin_base_384_arm_int8.ort", 132813600L),
        DataFileItem("classes.json", 364727L)
    )
}
