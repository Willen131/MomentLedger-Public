package com.momentledger.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentledger.app.R
import com.momentledger.app.ui.theme.AppleColors
import com.momentledger.app.ui.theme.LocalAppleColors
import com.momentledger.app.ui.theme.LocalIsDarkTheme

internal enum class BocchiMotif(@DrawableRes val charm: Int) {
    HAIRCLIPS(R.drawable.bocchi_gear_clips),
    DRUM(R.drawable.bocchi_gear_drum),
    VINYL(R.drawable.bocchi_gear_vinyl),
    PEDAL(R.drawable.bocchi_gear_pedal),
    BASS(R.drawable.bocchi_gear_bass);

    fun accent(colors: AppleColors): Color = when (this) {
        HAIRCLIPS -> colors.blue
        DRUM -> colors.orange
        VINYL, BASS -> colors.indigo
        PEDAL -> colors.red
    }
}

internal enum class BocchiStage(
    @DrawableRes val banner: Int,
    @DrawableRes val sticker: Int,
    val motif: BocchiMotif
) {
    CALENDAR(R.drawable.bocchi_banner_diary, R.drawable.bocchi_planner, BocchiMotif.HAIRCLIPS),
    STATS(R.drawable.bocchi_banner_drums, R.drawable.bocchi_rhythm, BocchiMotif.DRUM),
    REPORTS(R.drawable.bocchi_banner_records, R.drawable.bocchi_coins, BocchiMotif.VINYL),
    SETTINGS(R.drawable.bocchi_banner_amp, R.drawable.bocchi_amp, BocchiMotif.PEDAL)
}

@Composable
internal fun BocchiSticker(@DrawableRes resource: Int, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(resource),
        contentDescription = null,
        modifier = modifier
    )
}

@Composable
internal fun BocchiEmptyState(@DrawableRes artwork: Int, message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        BocchiSticker(artwork, Modifier.size(64.dp))
        Text(message, color = LocalAppleColors.current.subText, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun BocchiSheetHeading(
    text: String,
    @DrawableRes sticker: Int,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 16.sp
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = LocalAppleColors.current.text,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        BocchiSticker(sticker, Modifier.size(40.dp))
    }
}

@Composable
internal fun BocchiEmblem(
    glyph: ImageVector,
    motif: BocchiMotif,
    modifier: Modifier = Modifier,
    tint: Color = motif.accent(LocalAppleColors.current),
    contentDescription: String? = null
) {
    val colors = LocalAppleColors.current
    val dark = LocalIsDarkTheme.current
    val accent = motif.accent(colors)
    val shape = RoundedCornerShape(28)
    Box(modifier) {
        Box(
            Modifier.fillMaxSize(0.89f)
                .clip(shape)
                .bocchiGlassFill(lerp(colors.card, accent, if (dark) 0.23f else 0.16f), accent)
                .border(1.dp, bocchiGlassRim(), shape)
        )
        Icon(
            imageVector = glyph,
            contentDescription = contentDescription,
            tint = lerp(colors.text, tint, 0.54f),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = (-1.5).dp, y = (-1.5).dp)
                .fillMaxSize(0.63f)
        )
        BocchiSticker(motif.charm, Modifier.align(Alignment.BottomEnd).fillMaxSize(0.46f))
    }
}

@Composable
internal fun BocchiCategoryIcon(
    iconKey: String,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val artwork = when (iconKey.trim()) {
        "restaurant" -> R.drawable.bocchi_category_food
        "directions_car", "directions_bus" -> R.drawable.bocchi_category_transit
        "shopping_bag" -> R.drawable.bocchi_category_shopping
        "shopping_cart" -> R.drawable.bocchi_category_daily
        "sports_esports" -> R.drawable.bocchi_category_games
        "medical_services" -> R.drawable.bocchi_category_health
        "home" -> R.drawable.bocchi_category_home
        "phone_iphone" -> R.drawable.bocchi_category_phone
        "menu_book" -> R.drawable.bocchi_category_book
        "card_giftcard" -> R.drawable.bocchi_category_gift
        "payments" -> R.drawable.bocchi_category_salary
        "redeem" -> R.drawable.bocchi_category_envelope
        "receipt_long" -> R.drawable.bocchi_category_receipt
        "trending_up" -> R.drawable.bocchi_category_finance
        "currency_exchange" -> R.drawable.bocchi_category_exchange
        "local_cafe" -> R.drawable.bocchi_category_coffee
        "flight" -> R.drawable.bocchi_category_flight
        "fitness_center" -> R.drawable.bocchi_category_fitness
        "pets" -> R.drawable.bocchi_category_pet
        "account_balance_wallet" -> R.drawable.bocchi_category_wallet
        "photo_camera" -> R.drawable.bocchi_category_camera
        "directions_bike" -> R.drawable.bocchi_category_bicycle
        "local_drink" -> R.drawable.bocchi_category_drink
        "subway" -> R.drawable.bocchi_category_subway
        "wifi" -> R.drawable.bocchi_category_wifi
        "headphones" -> R.drawable.bocchi_category_headphones
        "school" -> R.drawable.bocchi_category_school
        else -> null
    }
    if (artwork != null) {
        Image(painterResource(artwork), contentDescription, modifier)
    } else {
        BocchiEmblem(
            glyph = categoryIconForKey(iconKey),
            motif = categoryMotif(iconKey.trim()),
            tint = tint,
            contentDescription = contentDescription,
            modifier = modifier
        )
    }
}

private fun categoryMotif(iconKey: String): BocchiMotif = when (iconKey) {
    "fastfood", "cookie", "bakery_dining", "cake", "local_grocery_store",
    "local_florist", "spa", "content_cut", "child_care", "cleaning_services",
    "local_laundry_service", "chair", "water_drop", "more_horiz" -> BocchiMotif.HAIRCLIPS
    "directions_car", "train", "hotel", "local_taxi",
    "local_parking", "local_gas_station", "luggage", "local_shipping", "cabin",
    "pool", "sports_soccer" -> BocchiMotif.DRUM
    "savings", "work", "business_center", "verified_user", "policy",
    "local_pharmacy", "volunteer_activism", "computer" -> BocchiMotif.BASS
    "movie", "music_note", "theater_comedy", "toys" -> BocchiMotif.VINYL
    else -> BocchiMotif.PEDAL
}
