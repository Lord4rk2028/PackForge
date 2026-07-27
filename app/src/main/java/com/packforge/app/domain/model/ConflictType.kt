package com.packforge.app.domain.model

enum class ConflictType {
    FILE_OVERLAP,
    ENTITY_IDENTIFIER,
    ITEM_IDENTIFIER,
    RECIPE_IDENTIFIER,
    SCRIPT_CONFLICT,
    VERSION_MISMATCH,
    TEXTURE_ATLAS,
    SOUND_OVERLAP,
    MANIFEST_UUID
}
