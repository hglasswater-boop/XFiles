from pathlib import Path

path = Path('app/src/main/java/app/local1st/files/ui/viewer/VideoPlayer.kt')
text = path.read_text()
old = '''                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // "≈" flags an untrustworthy rate: missing entirely, or a
                        // non-standard average — MP4 rates are often synthesized as
                        // frameCount/duration, which is meaningless for VFR screen
                        // recordings that only encode a frame when the screen changes.
                        val approx = if (fps > 0f && isStandardFps(fps)) "" else "≈"
                        val total = totalFramesNow()
                        val modeColor = if (frameMode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                        ModeToggleText(
                            text = if (frameMode) {
                                "$approx${(frameOf(positionMs).coerceIn(0L, (total - 1).coerceAtLeast(0L))) + 1}"
                            } else {
                                formatPlayTime(positionMs)
                            },
                            frameMode = frameMode,
                            color = modeColor,
                            onToggle = {
                                frameMode = !frameMode
                                interactionTick++
                            },
                        )
                        Slider(
                            value = sliderPos
                                ?: if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                            onValueChange = { v ->
                                if (durationMs > 0) {
                                    if (sliderPos == null) {
                                        // Pause while scrubbing: playback racing the
                                        // thumb fights the user, and a video ending
                                        // mid-drag would auto-advance under the finger.
                                        sliderWasPlaying = player.isPlaying
                                        player.pause()
                                    }
                                    sliderPos = v
                                    seekGate.request((v * durationMs).toLong())
                                }
                            },
                            onValueChangeFinished = {
                                sliderPos?.let { seekGate.request((it * durationMs).toLong()) }
                                sliderPos = null
                                if (sliderWasPlaying && !frameMode) player.play()
                                sliderWasPlaying = false
                                interactionTick++
                            },
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        )
                        ModeToggleText(
                            text = if (frameMode) {
                                "$approx$total · ${String.format(Locale.US, "%.1f", effFpsNow())}fps"
                            } else {
                                formatPlayTime(durationMs)
                            },
                            frameMode = frameMode,
                            color = modeColor,
                            onToggle = {
                                frameMode = !frameMode
                                interactionTick++
                            },
                        )
                    }
'''
new = '''                    // Keep the readouts above the scrubber so the timeline can use the
                    // full width of the control card instead of being squeezed between labels.
                    val approx = if (fps > 0f && isStandardFps(fps)) "" else "≈"
                    val total = totalFramesNow()
                    val modeColor = if (frameMode) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ModeToggleText(
                            text = if (frameMode) {
                                "$approx${(frameOf(positionMs).coerceIn(0L, (total - 1).coerceAtLeast(0L))) + 1}"
                            } else {
                                formatPlayTime(positionMs)
                            },
                            frameMode = frameMode,
                            color = modeColor,
                            onToggle = {
                                frameMode = !frameMode
                                interactionTick++
                            },
                        )
                        ModeToggleText(
                            text = if (frameMode) {
                                "$approx$total · ${String.format(Locale.US, "%.1f", effFpsNow())}fps"
                            } else {
                                formatPlayTime(durationMs)
                            },
                            frameMode = frameMode,
                            color = modeColor,
                            onToggle = {
                                frameMode = !frameMode
                                interactionTick++
                            },
                        )
                    }
                    Slider(
                        value = sliderPos
                            ?: if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                        onValueChange = { v ->
                            if (durationMs > 0) {
                                if (sliderPos == null) {
                                    // Pause while scrubbing: playback racing the
                                    // thumb fights the user, and a video ending
                                    // mid-drag would auto-advance under the finger.
                                    sliderWasPlaying = player.isPlaying
                                    player.pause()
                                }
                                sliderPos = v
                                seekGate.request((v * durationMs).toLong())
                            }
                        },
                        onValueChangeFinished = {
                            sliderPos?.let { seekGate.request((it * durationMs).toLong()) }
                            sliderPos = null
                            if (sliderWasPlaying && !frameMode) player.play()
                            sliderWasPlaying = false
                            interactionTick++
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
'''
if old not in text:
    raise SystemExit('target layout block not found')
path.write_text(text.replace(old, new, 1))
