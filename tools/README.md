# Demo recording tools

Reusable pipeline for README / store demo captures. It records an on-device screen capture and converts it to a GIF plus the source MP4.

The current README hero uses the static dual-pane screenshot rather than the old stitched Root/App Manager tour, because those features were removed from XFiles.

## Requirements

- `adb`, with the device connected and unlocked
- `ffmpeg` / `ffprobe` on the host
- `python3` for scripted UI element lookup

## Capture by hand

Record for a fixed window while you tap through the demo yourself:

```bash
tools/record-demo.sh myclip --duration 15 --width 340
```

Output: `docs/assets/myclip.gif` and `docs/assets/myclip.mp4`.

## Scripted file-copy demo

```bash
tools/record-demo.sh copy \
  --prep tools/demos/_reset-shallow.sh \
  --drive tools/demos/copy.sh \
  --width 340 \
  --duration 40
```

`--prep` runs off-camera before recording. `--drive` runs the tap sequence while recording. Drivers locate rows through the accessibility tree instead of fixed pixels, so they survive ordinary layout shifts and different screen sizes.

## Combine clips

`combine-demo.sh` is still available when a new multi-part tour is needed:

```bash
tools/combine-demo.sh tour --speed 2.0 --width 320 \
  docs/assets/clip-1.mp4 docs/assets/clip-2.mp4
```

Only combine captures of features that still exist in the current app.

## record-demo.sh options

`--serial S` · `--duration SEC` · `--width PX` · `--fps N` ·
`--crop-top/--crop-bottom PX` · `--bit-rate R` · `--cold` ·
`--touches` / `--no-touches` · `--prep SCRIPT` · `--drive SCRIPT` ·
`--out-dir DIR` (default `docs/assets`).

## Files

| File | Role |
|---|---|
| `record-demo.sh` | Record → GIF pipeline using screenrecord + ffmpeg |
| `combine-demo.sh` | Concatenate clips into one sped-up tour GIF |
| `demos/lib.sh` | Shared adb + uiautomator helpers |
| `demos/tree_state.py` | Parse a uiautomator dump for tree state |
| `demos/_reset.sh` | Thorough reset helper for future tree-oriented demos |
| `demos/_reset-shallow.sh` | Fast collapse-and-scroll-top prep |
| `demos/copy.sh` | Driver for direct copy into the other pane's visible destination |

When adding a scripted demo, keep selectors based on visible text or accessibility labels and update this file if the captured flow becomes part of project documentation.
