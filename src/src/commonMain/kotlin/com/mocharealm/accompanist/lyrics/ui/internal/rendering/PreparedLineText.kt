package com.mocharealm.accompanist.lyrics.ui.internal.rendering

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
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
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.LyricsCaptionPosition

/**
 * Scan the leading strong-directional character to decide whether the
 * translation should sweep right-to-left. The main line's reading order
 * is not enough: a Latin or CJK line can carry an Arabic or Hebrew
 * translation, and vice versa.
 */
private fun isRtlText(text: CharSequence): Boolean {
    for (ch in text) {
        val c = ch.code
        when {
            c in 0x0590..0x05FF -> return true  // Hebrew
            c in 0x0600..0x06FF -> return true  // Arabic
            c in 0x0700..0x074F -> return true  // Syriac
            c in 0x0750..0x077F -> return true  // Arabic Supplement
            c in 0x0780..0x07BF -> return true  // Thaana
            c in 0x07C0..0x07FF -> return true  // NKo
            c in 0x08A0..0x08FF -> return true  // Arabic Extended-A
            c in 0xFB1D..0xFB4F -> return true  // Hebrew presentation
            c in 0xFB50..0xFDFF -> return true  // Arabic presentation A
            c in 0xFE70..0xFEFF -> return true  // Arabic presentation B
            c in 0x10800..0x10FFF -> return true // Ancient RTL scripts
            c in 'a'.code..'z'.code -> return false
            c in 'A'.code..'Z'.code -> return false
            c in 0x3040..0x30FF -> return false // Hiragana / Katakana
            c in 0x4E00..0x9FFF -> return false // CJK Unified
            c in 0xAC00..0xD7AF -> return false // Hangul
        }
    }
    return false
}

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
    translationPosition: LyricsCaptionPosition = LyricsCaptionPosition.BELOW,
    phoneticPosition: LyricsCaptionPosition = LyricsCaptionPosition.ABOVE,
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
            val renderTranslation: @Composable () -> Unit = {
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

                            // Sweep direction follows the translation text itself,
                            // not the main line's reading order. A LTR main line
                            // can carry an Arabic translation (and vice versa).
                            val isRtl = isRtlText(layout.layoutInput.text.text)

                            // Dim base — always drawn so the text keeps its
                            // silhouette while the sweep is still approaching.
                            drawText(
                                layout,
                                color = dimColor,
                                topLeft = Offset(0f, lift),
                            )

                            // Distribute the sweep across the wrapped lines by
                            // their relative pixel widths, so a two-line
                            // translation reveals one line at a time instead of
                            // painting both simultaneously.
                            val lineCount = layout.lineCount
                            if (lineProgress > 0f && lineCount > 0) {
                                val lineWidths =
                                    FloatArray(lineCount) { i ->
                                        (layout.getLineRight(i) - layout.getLineLeft(i))
                                            .coerceAtLeast(0f)
                                    }
                                val totalLineWidth = lineWidths.sum().takeIf { it > 0f } ?: 1f
                                var cursor = 0f
                                for (i in 0 until lineCount) {
                                    val windowStart = cursor / totalLineWidth
                                    val windowEnd = (cursor + lineWidths[i]) / totalLineWidth
                                    cursor += lineWidths[i]
                                    val localProgress =
                                        if (windowEnd > windowStart) {
                                            ((lineProgress - windowStart) / (windowEnd - windowStart))
                                                .coerceIn(0f, 1f)
                                        } else {
                                            1f
                                        }
                                    if (localProgress <= 0f) continue

                                    val lineLeft = layout.getLineLeft(i)
                                    val lineRight = layout.getLineRight(i)
                                    val swept = (lineRight - lineLeft) * localProgress
                                    val clipStart = if (isRtl) lineRight - swept else lineLeft
                                    val clipEnd = if (isRtl) lineRight else lineLeft + swept

                                    clipRect(
                                        left = clipStart,
                                        top = layout.getLineTop(i),
                                        right = clipEnd,
                                        bottom = layout.getLineBottom(i),
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
            }
            val renderPhonetic: @Composable () -> Unit = {
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
            }
            for (line in prepared.before) PreparedLineText(
                line,
                playback = playback,
                resources = resources,
                currentTimeProvider = currentTimeProvider,
                showTranslation = showTranslation,
                showPhonetic = showPhonetic,
                showDebugRectangles = showDebugRectangles,
                translationPosition = translationPosition,
                phoneticPosition = phoneticPosition,
            )

            // Above captions — translation first, then phonetic.
            if (translationPosition == LyricsCaptionPosition.ABOVE) renderTranslation()
            if (phoneticPosition == LyricsCaptionPosition.ABOVE) renderPhonetic()
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
            val shouldShowBelowCaption =
                (translationPosition == LyricsCaptionPosition.BELOW &&
                    showTranslation &&
                    prepared.translation != null) ||
                    (phoneticPosition == LyricsCaptionPosition.BELOW &&
                        showPhonetic &&
                        prepared.phonetic != null)
            val hasAnyCaption = prepared.translation != null || prepared.phonetic != null
            if (hasAnyCaption) {
                val captionGap =
                    animateFloatAsState(
                        if (shouldShowBelowCaption) 8f else 0f,
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

            // Below captions — phonetic first, then translation.
            if (phoneticPosition == LyricsCaptionPosition.BELOW) renderPhonetic()
            if (translationPosition == LyricsCaptionPosition.BELOW) renderTranslation()
            for (line in prepared.after) PreparedLineText(
                line,
                playback = playback,
                resources = resources,
                currentTimeProvider = currentTimeProvider,
                showTranslation = showTranslation,
                showPhonetic = showPhonetic,
                showDebugRectangles = showDebugRectangles,
                translationPosition = translationPosition,
                phoneticPosition = phoneticPosition,
            )
        }
    }
}
