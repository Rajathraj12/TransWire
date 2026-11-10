package com.example.myapplication.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.R
import com.example.myapplication.presentation.theme.*
import kotlin.math.cos
import kotlin.math.sin

// ═══════════════════════════════════════════════════════════════════════════════
//  Spacing tokens
// ═══════════════════════════════════════════════════════════════════════════════

object TwSpacing {
    val XSmall  = 4.dp
    val Small   = 8.dp
    val Default = 16.dp
    val Medium  = 20.dp
    val Large   = 24.dp
    val XLarge  = 32.dp
    val XXLarge = 48.dp
}

// ═══════════════════════════════════════════════════════════════════════════════
//  File type classification
// ═══════════════════════════════════════════════════════════════════════════════

enum class FileCategory(
    val label: String,
    val lightColor: Color,
    val darkColor: Color,
    val icon: ImageVector
) {
    IMAGE(   "Image",    Color(0xFF0284C7), Color(0xFF38BDF8), Icons.Default.Image),
    VIDEO(   "Video",    Color(0xFF7C3AED), Color(0xFFA78BFA), Icons.Default.VideoFile),
    AUDIO(   "Audio",    Color(0xFFDB2777), Color(0xFFF472B6), Icons.Default.AudioFile),
    DOCUMENT("Document", Color(0xFF059669), Color(0xFF34D399), Icons.AutoMirrored.Filled.Article),
    ARCHIVE( "Archive",  Color(0xFFD97706), Color(0xFFFBBF24), Icons.Default.FolderZip),
    APK(     "App",      Color(0xFF0D9488), Color(0xFF2DD4BF), Icons.Default.Android),
    OTHER(   "File",     Color(0xFF475569), Color(0xFF94A3B8), Icons.AutoMirrored.Filled.InsertDriveFile)
}

fun mimeToCategory(mime: String?, fileName: String? = null): FileCategory {
    val normalizedMime = mime?.lowercase().orEmpty()
    val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
    return when {
        normalizedMime.startsWith("image/") -> FileCategory.IMAGE
        normalizedMime.startsWith("video/") -> FileCategory.VIDEO
        normalizedMime.startsWith("audio/") -> FileCategory.AUDIO
        normalizedMime.contains("pdf") || normalizedMime.contains("word") ||
            normalizedMime.contains("document") || normalizedMime.contains("spreadsheet") ||
            normalizedMime.contains("presentation") || normalizedMime.startsWith("text/") ||
            ext in setOf("pdf", "doc", "docx", "txt", "csv", "xls", "xlsx", "ppt", "pptx") -> FileCategory.DOCUMENT
        normalizedMime.contains("zip") || normalizedMime.contains("tar") ||
            normalizedMime.contains("rar") || normalizedMime.contains("7z") ||
            normalizedMime.contains("gzip") || ext in setOf("zip", "rar", "7z", "tar", "gz") -> FileCategory.ARCHIVE
        normalizedMime.contains("android.package-archive") || ext == "apk" -> FileCategory.APK
        else -> FileCategory.OTHER
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Status helpers
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun statusColor(status: String): Color = when (status) {
    "COMPLETED"             -> MaterialTheme.colorScheme.tertiary
    "IN_PROGRESS"           -> MaterialTheme.colorScheme.primary
    "PAUSED"                -> if (LocalIsDarkTheme.current) SemanticWarningDark else SemanticWarning
    "INTERRUPTED", "FAILED" -> MaterialTheme.colorScheme.error
    "CANCELLED"             -> MaterialTheme.colorScheme.onSurfaceVariant
    else                    -> MaterialTheme.colorScheme.onSurfaceVariant
}

fun statusLabel(status: String): String = when (status) {
    "COMPLETED"   -> "Completed"
    "IN_PROGRESS" -> "Active"
    "PAUSED"      -> "Paused"
    "INTERRUPTED" -> "Interrupted"
    "FAILED"      -> "Failed"
    "CANCELLED"   -> "Cancelled"
    "PENDING"     -> "Pending"
    else          -> status
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Brand Logo Mark (uses drawable/logo.png / ic_launcher_foreground)
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun TwBrandLogo(
    size: Dp = 40.dp,
    cornerRadius: Dp = 11.dp,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .size(size)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(cornerRadius)),
        shape = RoundedCornerShape(cornerRadius),
        color = Color.White
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "TransWire Logo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  TwTopBar
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor             = MaterialTheme.colorScheme.surface,
                titleContentColor          = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor     = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Tactile Buttons with subtle spring press + haptic feedback
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun TwPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessHigh),
        label = "btnScale"
    )
    Button(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        modifier = modifier.scale(scale),
        enabled = enabled,
        interactionSource = interactionSource,
        shape = ShapePill,
        colors = ButtonDefaults.buttonColors(
            containerColor         = MaterialTheme.colorScheme.primary,
            contentColor           = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            disabledContentColor   = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f)
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun TwSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessHigh),
        label = "secBtnScale"
    )
    Button(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        modifier = modifier.scale(scale),
        enabled = enabled,
        interactionSource = interactionSource,
        shape = ShapePill,
        colors = ButtonDefaults.buttonColors(
            containerColor         = MaterialTheme.colorScheme.secondary,
            contentColor           = MaterialTheme.colorScheme.onSecondary,
            disabledContainerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f),
            disabledContentColor   = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.65f)
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun TwOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessHigh),
        label = "outBtnScale"
    )
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.scale(scale),
        enabled = enabled,
        interactionSource = interactionSource,
        shape = ShapePill,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun TwDestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = ShapePill,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  TwCard — Architectural 1dp-bordered layered container
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun TwCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = ShapeDefault,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.985f else 1f,
        animationSpec = spring(Spring.DampingRatioLowBouncy, Spring.StiffnessHigh),
        label = "cardScale"
    )

    Surface(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .border(1.dp, borderColor, shape)
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onClick
                ) else Modifier
            ),
        shape = shape,
        color = backgroundColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(content = content)
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  File Category Badges & Icons
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun TwFileBadge(
    mimeType: String?,
    fileName: String? = null,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true
) {
    val dark = LocalIsDarkTheme.current
    val category = mimeToCategory(mimeType, fileName)
    val color = if (dark) category.darkColor else category.lightColor

    Surface(
        modifier = modifier,
        shape = ShapePill,
        color = color.copy(alpha = 0.13f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = category.icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp)
            )
            if (showLabel) {
                Text(
                    text = category.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun TwFileIconBadge(
    mimeType: String?,
    fileName: String? = null,
    size: Dp = 44.dp
) {
    val dark = LocalIsDarkTheme.current
    val category = mimeToCategory(mimeType, fileName)
    val color = if (dark) category.darkColor else category.lightColor

    Box(
        modifier = Modifier
            .size(size)
            .clip(ShapeSmall)
            .background(color.copy(alpha = 0.13f))
            .border(1.dp, color.copy(alpha = 0.22f), ShapeSmall),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = category.icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

@Composable
fun TwStatusChip(status: String, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    Surface(
        modifier = modifier,
        shape = ShapePill,
        color = color.copy(alpha = 0.13f)
    ) {
        Text(
            text = statusLabel(status),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun TwSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    action: Pair<String, () -> Unit>? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (action != null) {
            TextButton(
                onClick = action.second,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = action.first,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun TwDivider(
    modifier: Modifier = Modifier,
    startIndent: Dp = 0.dp
) {
    HorizontalDivider(
        modifier = modifier.padding(start = startIndent),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
fun TwEmptyState(
    title: String,
    subtitle: String,
    icon: ImageVector = Icons.Outlined.Inbox,
    modifier: Modifier = Modifier,
    action: Pair<String, () -> Unit>? = null
) {
    val infiniteTransition = rememberInfiniteTransition(label = "emptyAnim")
    val iconScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "emptyScale"
    )
    Column(
        modifier = modifier.padding(TwSpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TwSpacing.Small)
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .scale(iconScale)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(38.dp)
            )
        }
        Spacer(Modifier.height(TwSpacing.XSmall))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Spacer(Modifier.height(TwSpacing.Small))
            TwPrimaryButton(text = action.first, onClick = action.second, leadingIcon = Icons.Default.Add)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  Canvas Visualizations: Sonar Rings, Precision Telemetry Ring & Animated Check
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun TwPulseRing(
    color: Color,
    modifier: Modifier = Modifier,
    ringCount: Int = 3,
    maxRadiusFraction: Float = 0.95f
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val animations = (0 until ringCount).map { i ->
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2400, delayMillis = i * (2400 / ringCount), easing = LinearOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "ring$i"
        )
    }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        animations.forEach { anim ->
            val radius = (size.minDimension / 2f) * (0.28f + anim.value * (maxRadiusFraction - 0.28f))
            drawCircle(
                color = color.copy(alpha = (1f - anim.value) * 0.45f),
                radius = radius,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

@Composable
fun TwProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    progressColor: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 14.dp,
    tickCount: Int = 0,
    tickTrackColor: Color = MaterialTheme.colorScheme.outline,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val stroke = strokeWidth.toPx()
            val tickSpace = if (tickCount > 0) 14.dp.toPx() else 0f
            val diameter = (size.minDimension - stroke - tickSpace * 2).coerceAtLeast(10f)
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            val center = Offset(size.width / 2f, size.height / 2f)

            if (tickCount > 0) {
                val outerR = size.minDimension / 2f - 2.dp.toPx()
                val innerR = outerR - 6.dp.toPx()
                val litTicks = (clamped * tickCount).toInt()
                for (i in 0 until tickCount) {
                    val angleRad = Math.toRadians((-90.0 + i * (360.0 / tickCount)))
                    val cosA = cos(angleRad).toFloat()
                    val sinA = sin(angleRad).toFloat()
                    val start = Offset(center.x + innerR * cosA, center.y + innerR * sinA)
                    val end = Offset(center.x + outerR * cosA, center.y + outerR * sinA)
                    val active = i < litTicks
                    drawLine(
                        color = if (active) progressColor else tickTrackColor,
                        start = start,
                        end = end,
                        strokeWidth = if (active) 2.2.dp.toPx() else 1.4.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            // Track
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            // Active sweep
            val sweep = (clamped * 360f).coerceAtLeast(if (clamped > 0f) 2f else 0f)
            if (sweep > 0f) {
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        content()
    }
}

@Composable
fun TwAnimatedCheckmark(
    color: Color,
    modifier: Modifier = Modifier.size(48.dp)
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing)
        )
    }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val fullPath = Path().apply {
            moveTo(w * 0.22f, h * 0.52f)
            lineTo(w * 0.42f, h * 0.72f)
            lineTo(w * 0.78f, h * 0.30f)
        }
        val dst = Path()
        PathMeasure().apply {
            setPath(fullPath, false)
            getSegment(0f, length * progress.value, dst, true)
        }
        drawPath(
            path = dst,
            color = color,
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  TwBottomNav — Tactile floating pill dock
// ═══════════════════════════════════════════════════════════════════════════════

data class TwNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
)

val twNavItems = listOf(
    TwNavItem("home",     "Home",     Icons.Outlined.Home,     Icons.Filled.Home),
    TwNavItem("history",  "History",  Icons.Outlined.History,  Icons.Filled.History),
    TwNavItem("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings)
)

@Composable
fun TwBottomNav(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TwSpacing.Large, vertical = TwSpacing.Default)
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = ShapePill,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                twNavItems.forEach { item ->
                    val selected = currentRoute == item.route
                    val interactionSource = remember { MutableInteractionSource() }

                    val bgColor by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
                                      else Color.Transparent,
                        animationSpec = tween(220),
                        label = "navBg${item.route}"
                    )
                    val contentColor by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.primary
                                      else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(220),
                        label = "navFg${item.route}"
                    )

                    Row(
                        modifier = Modifier
                            .clip(ShapePill)
                            .background(bgColor)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) {
                                if (!selected) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onNavigate(item.route)
                                }
                            }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (selected) item.selectedIcon else item.icon,
                            contentDescription = item.label,
                            tint = contentColor,
                            modifier = Modifier.size(20.dp)
                        )
                        AnimatedVisibility(
                            visible = selected,
                            enter = expandHorizontally(tween(200)) + fadeIn(tween(200)),
                            exit = shrinkHorizontally(tween(180)) + fadeOut(tween(140))
                        ) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = contentColor
                            )
                        }
                    }
                }
            }
        }
    }
}
