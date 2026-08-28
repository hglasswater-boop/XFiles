from pathlib import Path

path = Path("app/src/tv/java/app/local1st/files/ui/browser/PaneView.kt")
text = path.read_text()
old = '''        if (listState.layoutInfo.visibleItemsInfo.none { it.key == targetKey }) {
            listState.scrollToItem(targetIndex)
        }
        withFrameNanos { }

        val requester = if (targetIndex == 0) {
            tvInitialFocusRequester
        } else {
            tvRowFocusRequesters.getOrPut(targetKey) { FocusRequester() }
        }
        runCatching { requester.requestFocus() }
    }
'''
new = '''        if (listState.layoutInfo.visibleItemsInfo.none { it.key == targetKey }) {
            listState.scrollToItem(targetIndex)
        }

        val requester = if (targetIndex == 0) {
            tvInitialFocusRequester
        } else {
            tvRowFocusRequesters.getOrPut(targetKey) { FocusRequester() }
        }
        repeat(8) {
            withFrameNanos { }
            val focused = runCatching { requester.requestFocus() }.getOrDefault(false)
            if (focused) return@LaunchedEffect
        }
        // Allow the same D-pad target to be requested again after a transient focus failure.
        if (tvRequestedRowKey == targetKey) tvRequestedRowKey = null
    }
'''
if old not in text:
    raise SystemExit("requested row focus block not found")
path.write_text(text.replace(old, new, 1))
