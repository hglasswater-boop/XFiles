from pathlib import Path

path = Path("app/src/main/java/app/local1st/files/ui/viewer/VideoPlayer.kt")
text = path.read_text()

old = """                                        VideoGestureMode.HORIZONTAL_SEEK -> {
                                            scrubbing = true
                                            resumeAfterSeek = player.playWhenReady
                                            player.pause()
                                            baseMs = anchorMs()
                                            baseFrame = frameOf(baseMs)
                                        }"""
new = """                                        VideoGestureMode.HORIZONTAL_SEEK -> {
                                            scrubbing = true
                                            resumeAfterSeek = player.playWhenReady
                                            player.pause()
                                            baseMs = anchorMs()
                                            baseFrame = frameOf(baseMs)
                                            // Ignore the movement used to recognize the drag so
                                            // seeking starts smoothly instead of jumping immediately.
                                            accumX = 0f
                                            accumY = 0f
                                        }"""
if old not in text:
    raise SystemExit("horizontal seek start block not found")
text = text.replace(old, new, 1)

old = """                        if (hasPrevious || hasNext) {
                            TooltipIconButton(
                                stringResource(R.string.next_video),
                                Icons.Outlined.SkipNext,
                                enabled = hasNext,
                            ) {
                                player.seekToNextMediaItem()
                                interactionTick++
                            }
                        }
                    }
                }
            }
        }
    }
}"""
new = """                        if (hasPrevious || hasNext) {
                            TooltipIconButton(
                                stringResource(R.string.next_video),
                                Icons.Outlined.SkipNext,
                                enabled = hasNext,
                            ) {
                                player.seekToNextMediaItem()
                                interactionTick++
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        VideoOrientationQuickControls {
                            interactionTick++
                        }
                    }
                }
            }
        }
    }
}"""
if old not in text:
    raise SystemExit("bottom controls block not found")
text = text.replace(old, new, 1)

old = "private const val SEEK_THROTTLE_MS = 150L"
if old not in text:
    raise SystemExit("seek throttle constant not found")
text = text.replace(old, "private const val SEEK_THROTTLE_MS = 80L", 1)

old = "private const val TIME_SWIPE_MS_PER_DP = 100L"
if old not in text:
    raise SystemExit("swipe sensitivity constant not found")
text = text.replace(old, "private const val TIME_SWIPE_MS_PER_DP = 40L", 1)

path.write_text(text)
