package com.example.myapplication.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val TranswireShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small      = RoundedCornerShape(10.dp),
    medium     = RoundedCornerShape(16.dp),
    large      = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

// ── Design-token shortcuts ─────────────────────────────────────────────────────
object TwRadius {
    val XSmall   = 4.dp
    val Small    = 10.dp
    val Default  = 16.dp
    val Large    = 24.dp
    val XLarge   = 32.dp
    val Full     = 100.dp           // Pills / chips
}

val ShapeSmall   = RoundedCornerShape(TwRadius.Small)
val ShapeDefault = RoundedCornerShape(TwRadius.Default)
val ShapeLarge   = RoundedCornerShape(TwRadius.Large)
val ShapeXLarge  = RoundedCornerShape(TwRadius.XLarge)
val ShapePill    = RoundedCornerShape(TwRadius.Full)
val ShapeSheet   = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

