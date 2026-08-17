from pathlib import Path

path = Path("app/src/main/java/app/local1st/files/ui/viewer/VideoPlayer.kt")
text = path.read_text()

old_open = """                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
"""
new_open = """                    Box(Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.align(Alignment.Center),
                        ) {
"""
if old_open not in text:
    raise SystemExit("playback row opening not found")
text = text.replace(old_open, new_open, 1)

old_tail = """                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        VideoOrientationQuickControls(orientationController) {
                            interactionTick++
                        }
                    }
"""
new_tail = """                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        ) {
                            VideoOrientationQuickControls(orientationController) {
                                interactionTick++
                            }
                        }
                    }
"""
if old_tail not in text:
    raise SystemExit("orientation row tail not found")

path.write_text(text.replace(old_tail, new_tail, 1))
