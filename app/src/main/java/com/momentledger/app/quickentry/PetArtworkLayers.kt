package com.momentledger.app.quickentry

import androidx.annotation.DrawableRes
import com.momentledger.app.R

internal const val PET_ART_SIZE = 220f

internal enum class OverlayPet(
    val id: String,
    val label: String,
    @DrawableRes val portrait: Int,
    @DrawableRes val headLayer: Int,
    @DrawableRes val eyesAtlas: Int
) {
    HITORI(
        id = "hitori",
        label = "后藤一里",
        portrait = R.drawable.bocchi_hitori,
        headLayer = R.drawable.bocchi_hitori_layer_head,
        eyesAtlas = R.drawable.bocchi_hitori_eyes_atlas
    ),
    RYO(
        id = "ryo",
        label = "山田凉",
        portrait = R.drawable.bocchi_ryo,
        headLayer = R.drawable.bocchi_ryo_layer_head,
        eyesAtlas = R.drawable.bocchi_ryo_eyes_atlas
    ),
    NIJIKA(
        id = "nijika",
        label = "伊地知虹夏",
        portrait = R.drawable.bocchi_nijika,
        headLayer = R.drawable.bocchi_nijika_layer_head,
        eyesAtlas = R.drawable.bocchi_nijika_eyes_atlas
    ),
    KITA(
        id = "kita",
        label = "喜多郁代",
        portrait = R.drawable.bocchi_kita,
        headLayer = R.drawable.bocchi_kita_layer_head,
        eyesAtlas = R.drawable.bocchi_kita_eyes_atlas
    );

    companion object {
        fun fromId(id: String): OverlayPet = entries.firstOrNull { it.id == id } ?: HITORI
    }
}
