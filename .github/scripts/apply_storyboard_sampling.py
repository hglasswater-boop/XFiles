from pathlib import Path
import re

path = Path("app/src/mobile/java/app/local1st/files/ui/browser/VideoStoryboardDialog.kt")
text = path.read_text(encoding="utf-8")

old_version = "    private const val CACHE_VERSION = 4\n"
new_version = "    private const val CACHE_VERSION = 5\n"
if old_version not in text:
    raise SystemExit("CACHE_VERSION marker not found")
text = text.replace(old_version, new_version, 1)

replacement = '''    private fun sampleTimes(
        durationMs: Long,
        count: Int,
        minSpacingMs: Long,
    ): List<Long> {
        if (durationMs <= 0L || count <= 0) return emptyList()
        val requestedCount = count.coerceAtLeast(1)
        val safeSpacingMs = minSpacingMs.coerceAtLeast(1_000L)

        // Keep long videos close to the actual beginning/end instead of dropping 5% at each edge.
        // For example, a 3-hour video now starts around 30 seconds rather than 9 minutes.
        val edgeInset = minOf(
            durationMs / 10L,
            maxOf(5_000L, minOf(30_000L, durationMs / 100L)),
        )
        val start = edgeInset.coerceAtLeast(0L)
        val end = (durationMs - edgeInset).coerceAtLeast(start)
        val span = (end - start).coerceAtLeast(0L)

        val maxSlot = span / safeSpacingMs
        val maxCountForSpacing = (maxSlot + 1L)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val actualCount = minOf(requestedCount, maxCountForSpacing)
        if (actualCount == 1) return listOf(start + span / 2L)

        // Give the beginning more resolution without sacrificing middle/end coverage. Quantizing
        // to spacing slots keeps the configured minimum interval even where sampling is denser.
        val lastIndex = actualCount - 1
        val times = ArrayList<Long>(actualCount)
        var previousSlot = -1L
        for (index in 0..lastIndex) {
            val fraction = index.toDouble() / lastIndex.toDouble()
            val curvedFraction = fraction * (0.35 + 0.65 * fraction)
            val desiredSlot = (maxSlot.toDouble() * curvedFraction).toLong()
            val minSlot = previousSlot + 1L
            val maxAllowedSlot = maxSlot - (lastIndex - index).toLong()
            val slot = desiredSlot.coerceIn(minSlot, maxAllowedSlot)
            times += start + slot * safeSpacingMs
            previousSlot = slot
        }
        return times
    }

'''

pattern = re.compile(
    r"    private fun sampleTimes\(.*?\n    private fun fallbackTimes\(",
    re.DOTALL,
)
match = pattern.search(text)
if match is None:
    raise SystemExit("sampleTimes block not found")
text = text[: match.start()] + replacement + "    private fun fallbackTimes(" + text[match.end() :]
path.write_text(text, encoding="utf-8")
