package com.mocharealm.accompanist.lyrics.ui.internal.rendering

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.ui.internal.effects.LyricsReveal
import com.mocharealm.accompanist.lyrics.ui.internal.effects.LyricsRevealSpring
import com.mocharealm.accompanist.lyrics.ui.internal.diagnostics.traceLyrics
import com.mocharealm.accompanist.lyrics.ui.internal.diagnostics.lyricsTraceEnabled
import com.mocharealm.accompanist.lyrics.ui.internal.diagnostics.setLyricsTraceCounter
import com.mocharealm.accompanist.lyrics.ui.internal.playback.LyricsPlaybackState
import com.mocharealm.accompanist.lyrics.ui.preparation.PreparedLine

@Composable
internal fun PreparedLineText(
    prepared: PreparedLine,
    playback: LyricsPlaybackState,
    resources: LyricsRenderResources,
    currentTimeProvider: () -> Int,
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 8.dp,
    showTranslation: Boolean = true,
    showPhonetic: Boolean = true,
    showDebugRectangles: Boolean = false,
) {
    val currentTime by rememberUpdatedState(currentTimeProvider)
    val density = LocalDensity.current
    val activeColor = resources.color
    val rasterState = resources.rasterState(prepared)
    val raster by rasterState
    val paints = remember(activeColor) { RowPaints(activeColor) }
    DisposableEffect(resources, prepared) {
        resources.retain(prepared)
        onDispose { resources.release(prepared) }
    }
    LaunchedEffect(resources, prepared) { resources.prepareRaster(prepared) }
    val accompanimentAlpha =
        if (prepared.source is KaraokeLine.AccompanimentKaraokeLine) 0.6f else 1f
    // Prepared geometry already resolves reading direction into a physical edge.
    val alignment = if (prepared.rightAligned) AbsoluteAlignment.Right else AbsoluteAlignment.Left
    LyricsReveal(
        visible = playback.line(prepared).visible.value,
        animateInitial = prepared.source is KaraokeLine.AccompanimentKaraokeLine,
        origin =
            if (prepared.source is KaraokeLine.AccompanimentKaraokeLine)
                androidx.compose.ui.graphics.TransformOrigin(
                    if (prepared.rightAligned) 1f else 0f,
                    if (prepared.revealFromBottom) 1f else 0f,
                )
            else androidx.compose.ui.graphics.TransformOrigin.Center,
    ) {
        Column(
            modifier
                .graphicsLayer { alpha = accompanimentAlpha }
                .fillMaxWidth()
                .padding(
                    vertical = verticalPadding,
                    horizontal =
                        if (prepared.source is KaraokeLine.AccompanimentKaraokeLine) 0.dp else 16.dp,
                ),
            horizontalAlignment = alignment,
        ) {
            for (line in prepared.before) PreparedLineText(
                line,
                playback = playback,
                resources = resources,
                currentTimeProvider = currentTimeProvider,
                showTranslation = showTranslation,
                showPhonetic = showPhonetic,
                showDebugRectangles = showDebugRectangles,
            )
            // Separate draw scopes mean a ticking row cannot invalidate its static neighbours.
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = alignment,
            ) {
                for ((index, row) in prepared.rows.withIndex()) {
                    val rowPlayback = playback.row(row)
                    // Timeline state selects the rows that need animation. Read the actual
                    // position in draw so snapshotFlow scheduling cannot delay their sweep.
                    // Inactive rows, including gaps within a row, keep their frozen position
                    // and do not observe the ticking provider.
                    val drawTime = remember(rowPlayback) {
                        {
                            if (rowPlayback.isAnimating.value) {
                                val now = currentTime()
                                if (lyricsTraceEnabled()) {
                                    setLyricsTraceCounter("Lyrics.drawPositionMs", now.toLong())
                                    setLyricsTraceCounter("Lyrics.timelineLagMs",
                                        now.toLong() - rowPlayback.time.intValue)
                                }
                                now
                            } else rowPlayback.time.intValue
                        }
                    }
                    val renderState = resources.row(row)
                    val layers = raster?.rows?.getOrNull(index)
                    val textHeight = row.height - row.phoneticHeight
                    Box(
                        Modifier.fillMaxWidth()
                            .height(with(density) { textHeight.toDp() })
                            .drawWithCache {
                                val glows =
                                    layers
                                        ?.takeIf { it.hasGlow }
                                        ?.let {
                                            traceLyrics("Lyrics.layerCache") { RowGlowLayers(this, it) }
                                        }
                                onDrawBehind {
                                    val preparedLayers = layers ?: return@onDrawBehind
                                    translate(top = -row.top) {
                                        drawPreparedRow(
                                            row,
                                            drawTime(),
                                            renderState,
                                            activeColor,
                                            paints,
                                            showDebugRectangles,
                                            preparedLayers,
                                            glows,
                                            drawPhonetics = false,
                                        )
                                    }
                                }
                            }
                    )
                    if (row.phoneticHeight > 0f) {
                        // Playback only sweeps pronunciation. Text lift/glow stay above;
                        // caption visibility uses the same reveal as translations.
                        val rowWidth = row.width
                        val followingGap =
                            prepared.rows.getOrNull(index + 1)?.phoneticSpacingBefore ?: 0f
                        LyricsReveal(showPhonetic, keepContent = true) {
                            Column {
                                Box(
                                    Modifier.size(
                                        with(density) { rowWidth.toDp() },
                                        with(density) { row.phoneticHeight.toDp() },
                                    ).drawWithCache {
                                        // Layout rounds caption width to pixels; compensate here
                                        // so end-aligned pronunciation keeps the original x origin.
                                        val rowLeft =
                                            if (prepared.rightAligned) prepared.width - size.width
                                            else 0f
                                        onDrawBehind {
                                            val preparedLayers = layers ?: return@onDrawBehind
                                            translate(left = -rowLeft, top = -row.top - textHeight) {
                                                drawPreparedRow(
                                                    row,
                                                    drawTime(),
                                                    renderState,
                                                    activeColor,
                                                    paints,
                                                    showDebugRectangles,
                                                    preparedLayers,
                                                    drawOriginal = false,
                                                )
                                            }
                                        }
                                    }
                                )
                                if (followingGap > 0f)
                                    Spacer(Modifier.height(with(density) { followingGap.toDp() }))
                            }
                        }
                    }
                }
            }
            if (prepared.translation != null || prepared.phonetic != null) {
                val captionGap =
                    animateFloatAsState(
                        if (
                            (showTranslation && prepared.translation != null) ||
                                (showPhonetic && prepared.phonetic != null)
                        )
                            8f
                        else 0f,
                        LyricsRevealSpring,
                        label = "captionGap",
                    )
                Spacer(
                    Modifier.layout { _, constraints ->
                        layout(
                            constraints.minWidth,
                            constraints.constrainHeight(captionGap.value.dp.roundToPx()),
                        ) {}
                    }
                )
            }
            prepared.translation?.let { layout ->
                LyricsReveal(showTranslation, keepContent = true) {
                    Canvas(
                        Modifier.size(
                            with(density) { layout.size.width.toDp() },
                            with(density) { layout.size.height.toDp() },
                        )
                    ) {
                        // Sweep the translation across the whole line: the
                        // bright portion follows the playback position from
                        // the line's start to its end, mirroring what the
                        // renderer already does for the main lyrics. RTL
                        // lines sweep from the right edge.
                        val now = currentTime()
                        val lineStart = prepared.source.start
                        val lineEnd =
                            prepared.source.end.coerceAtLeast(lineStart + 1)
                        val lineDuration = (lineEnd - lineStart).toFloat()
                        val lineProgress =
                            if (lineDuration > 0f) {
                                ((now - lineStart).toFloat() / lineDuration)
                                    .coerceIn(0f, 1f)
                            } else {
                                1f
                            }

                        // Rise: matches the main lyrics' lift curve. Starts
                        // 4px below the settled baseline and rises to 0.
                        val liftProgress =
                            ((now - lineStart).toFloat() / 700f).coerceIn(0f, 1f)
                        val lift = 4f * (1f - liftProgress) * (1f - liftProgress)

                        val baseAlpha =
                            activeColor.alpha * FocusedRowUnlitAlpha
                        val dimColor = activeColor.copy(alpha = baseAlpha * 0.35f)
                        val brightColor = activeColor.copy(alpha = baseAlpha)

                        val isRtl = prepared.rightAligned
                        val totalWidth = size.width
                        val sweepX = totalWidth * lineProgress

                        // Dim base — always drawn so the text keeps its
                        // silhouette while the sweep is still approaching.
                        drawText(
                            layout,
                            color = dimColor,
                            topLeft = Offset(0f, lift),
                        )

                        // Bright overlay clipped to the swept portion.
                        if (lineProgress > 0f) {
                            if (isRtl) {
                                clipRect(
                                    left = totalWidth - sweepX,
                                    top = 0f,
                                    right = totalWidth,
                                    bottom = size.height,
                                ) {
                                    drawText(
                                        layout,
                                        color = brightColor,
                                        topLeft = Offset(0f, lift),
                                    )
                                }
                            } else {
                                clipRect(
                                    left = 0f,
                                    top = 0f,
                                    right = sweepX,
                                    bottom = size.height,
                                ) {
                                    drawText(
                                        layout,
                                        color = brightColor,
                                        topLeft = Offset(0f, lift),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            prepared.phonetic?.let { layout ->
                LyricsReveal(showPhonetic, keepContent = true) {
                    Canvas(
                        Modifier.size(
                            with(density) { layout.size.width.toDp() },
                            with(density) { layout.size.height.toDp() },
                        )
                    ) {
                        drawText(layout, paints.phoneticColor)
                    }
                }
            }
            for (line in prepared.after) PreparedLineText(
                line,
                playback = playback,
                resources = resources,
                currentTimeProvider = currentTimeProvider,
                showTranslation = showTranslation,
                showPhonetic = showPhonetic,
                showDebugRectangles = showDebugRectangles,
            )
        }
    }
}
