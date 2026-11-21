package com.momentledger.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BakeryDining
import androidx.compose.material.icons.rounded.BusinessCenter
import androidx.compose.material.icons.rounded.Cabin
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Cookie
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.Fastfood
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LocalDrink
import androidx.compose.material.icons.rounded.LocalGroceryStore
import androidx.compose.material.icons.rounded.LocalLaundryService
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.LocalPharmacy
import androidx.compose.material.icons.rounded.LocalTaxi
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Pool
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.Subway
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material.icons.rounded.Toys
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Material Rounded 自定义分类图标扩充目录。
 *
 * key 统一使用 snake_case，value 为「中文可读名称 to 图标」。
 * 所有 key 与 [supportedCategoryIconKeys] 中的现有图标 key 不重复，
 * 图标也避开了 [categoryIconForKey] 已使用的视觉图标。
 */
internal val extraCategoryIcons: Map<String, Pair<String, ImageVector>> = mapOf(
    "local_grocery_store" to ("菜果" to Icons.Rounded.LocalGroceryStore),
    "fastfood" to ("快餐" to Icons.Rounded.Fastfood),
    "cookie" to ("零食" to Icons.Rounded.Cookie),
    "bakery_dining" to ("烘焙" to Icons.Rounded.BakeryDining),
    "cake" to ("甜点" to Icons.Rounded.Cake),
    "local_drink" to ("饮品" to Icons.Rounded.LocalDrink),
    "local_laundry_service" to ("洗衣" to Icons.Rounded.LocalLaundryService),
    "spa" to ("美容" to Icons.Rounded.Spa),
    "content_cut" to ("理发" to Icons.Rounded.ContentCut),
    "child_care" to ("儿童" to Icons.Rounded.ChildCare),
    "toys" to ("玩具" to Icons.Rounded.Toys),
    "cleaning_services" to ("日用品" to Icons.Rounded.CleaningServices),
    "local_parking" to ("停车" to Icons.Rounded.LocalParking),
    "directions_bike" to ("骑行" to Icons.AutoMirrored.Rounded.DirectionsBike),
    "cabin" to ("露营" to Icons.Rounded.Cabin),
    "music_note" to ("音乐" to Icons.Rounded.MusicNote),
    "wifi" to ("网络" to Icons.Rounded.Wifi),
    "business_center" to ("办公" to Icons.Rounded.BusinessCenter),
    "policy" to ("保险" to Icons.Rounded.Policy),
    "volunteer_activism" to ("公益" to Icons.Rounded.VolunteerActivism),
    "local_pharmacy" to ("药品" to Icons.Rounded.LocalPharmacy),
    "pool" to ("游泳" to Icons.Rounded.Pool),
    "sports_soccer" to ("球类" to Icons.Rounded.SportsSoccer),
    "school" to ("教育" to Icons.Rounded.School),
    "computer" to ("电脑" to Icons.Rounded.Computer),
    "headphones" to ("耳机" to Icons.Rounded.Headphones),
    "theater_comedy" to ("演出" to Icons.Rounded.TheaterComedy),
    "subway" to ("地铁" to Icons.Rounded.Subway),
    "local_taxi" to ("打车" to Icons.Rounded.LocalTaxi),
    "ev_station" to ("充电" to Icons.Rounded.EvStation),
    "handyman" to ("五金" to Icons.Rounded.Handyman),
    "water_drop" to ("水费" to Icons.Rounded.WaterDrop)
)
