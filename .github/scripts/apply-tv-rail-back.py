from pathlib import Path

rail_path = Path("app/src/tv/java/app/local1st/files/ui/main/EditionSideActionMenu.kt")
rail = rail_path.read_text()

old_data = '''private data class TvRailOpenRequest(
    val left: Int = 0,
    val right: Int = 0,
)

private val tvRailOpenRequest = mutableStateOf(TvRailOpenRequest())

internal fun requestEditionSideRail(left: Boolean) {
    val current = tvRailOpenRequest.value
    tvRailOpenRequest.value = if (left) {
        current.copy(left = current.left + 1)
    } else {
        current.copy(right = current.right + 1)
    }
}
'''
new_data = '''private data class TvRailOpenRequest(
    val left: Int = 0,
    val right: Int = 0,
    val close: Int = 0,
)

private val tvRailOpenRequest = mutableStateOf(TvRailOpenRequest())
private val tvRailVisible = mutableStateOf(false)

internal fun requestEditionSideRail(left: Boolean) {
    tvRailVisible.value = true
    val current = tvRailOpenRequest.value
    tvRailOpenRequest.value = if (left) {
        current.copy(left = current.left + 1)
    } else {
        current.copy(right = current.right + 1)
    }
}

internal fun isEditionSideRailOpen(): Boolean = tvRailVisible.value

internal fun dismissEditionSideRail(): Boolean {
    if (!tvRailVisible.value) return false
    tvRailVisible.value = false
    val current = tvRailOpenRequest.value
    tvRailOpenRequest.value = current.copy(close = current.close + 1)
    requestTvBrowserFocusReturn()
    return true
}

private fun markEditionSideRailClosed() {
    tvRailVisible.value = false
}
'''
if old_data not in rail:
    raise SystemExit("rail open request block not found")
rail = rail.replace(old_data, new_data, 1)

old_calls = '''        FocusRevealRail(
            alignment = Alignment.CenterStart,
            status = leftStatus,
            actions = leftActions,
            openRequest = railOpenRequest.left,
        )
        FocusRevealRail(
            alignment = Alignment.CenterEnd,
            actions = rightActions,
            openRequest = railOpenRequest.right,
        )
'''
new_calls = '''        FocusRevealRail(
            alignment = Alignment.CenterStart,
            status = leftStatus,
            actions = leftActions,
            openRequest = railOpenRequest.left,
            closeRequest = railOpenRequest.close,
        )
        FocusRevealRail(
            alignment = Alignment.CenterEnd,
            actions = rightActions,
            openRequest = railOpenRequest.right,
            closeRequest = railOpenRequest.close,
        )
'''
if old_calls not in rail:
    raise SystemExit("rail calls block not found")
rail = rail.replace(old_calls, new_calls, 1)

sig_old = '''private fun BoxScope.FocusRevealRail(
    alignment: Alignment,
    status: String? = null,
    actions: List<TvRailAction>,
    openRequest: Int,
) {
'''
sig_new = '''private fun BoxScope.FocusRevealRail(
    alignment: Alignment,
    status: String? = null,
    actions: List<TvRailAction>,
    openRequest: Int,
    closeRequest: Int,
) {
'''
if sig_old not in rail:
    raise SystemExit("FocusRevealRail signature not found")
rail = rail.replace(sig_old, sig_new, 1)

open_effect = '''    LaunchedEffect(openRequest, firstEnabledIndex) {
        if (openRequest > 0 && firstEnabledIndex >= 0) {
            visible = true
        }
    }

'''
open_close_effect = '''    LaunchedEffect(openRequest, firstEnabledIndex) {
        if (openRequest > 0 && firstEnabledIndex >= 0) {
            visible = true
        }
    }

    LaunchedEffect(closeRequest) {
        if (closeRequest > 0) {
            visible = false
            railHadFocus = false
            railHasFocus = false
        }
    }

'''
if open_effect not in rail:
    raise SystemExit("rail open effect not found")
rail = rail.replace(open_effect, open_close_effect, 1)

collapse_old = '''            if (!railHasFocus) {
                railHadFocus = false
                visible = false
            }
'''
collapse_new = '''            if (!railHasFocus) {
                railHadFocus = false
                visible = false
                markEditionSideRailClosed()
            }
'''
if collapse_old not in rail:
    raise SystemExit("rail collapse block not found")
rail = rail.replace(collapse_old, collapse_new, 1)

left_old = '''                                            Key.DirectionLeft -> {
                                                if (alignment == Alignment.CenterEnd) {
                                                    visible = false
                                                    railHadFocus = false
                                                    railHasFocus = false
                                                    requestTvBrowserFocusReturn()
                                                }
                                                true
                                            }
                                            Key.DirectionRight -> {
                                                if (alignment == Alignment.CenterStart) {
                                                    visible = false
                                                    railHadFocus = false
                                                    railHasFocus = false
                                                    requestTvBrowserFocusReturn()
                                                }
                                                true
                                            }
'''
left_new = '''                                            Key.DirectionLeft -> {
                                                if (alignment == Alignment.CenterEnd) {
                                                    dismissEditionSideRail()
                                                }
                                                true
                                            }
                                            Key.DirectionRight -> {
                                                if (alignment == Alignment.CenterStart) {
                                                    dismissEditionSideRail()
                                                }
                                                true
                                            }
'''
if left_old not in rail:
    raise SystemExit("rail horizontal exit block not found")
rail = rail.replace(left_old, left_new, 1)
rail_path.write_text(rail)

main_path = Path("app/src/main/java/app/local1st/files/ui/main/MainScreen.kt")
main = main_path.read_text()
state_anchor = '''    val isTvEdition = BuildConfig.APPLICATION_ID.endsWith(".tv")
    val wideLayout = !isTvEdition && LocalConfiguration.current.screenWidthDp >= 700
    val context = LocalContext.current
'''
state_replacement = '''    val isTvEdition = BuildConfig.APPLICATION_ID.endsWith(".tv")
    val wideLayout = !isTvEdition && LocalConfiguration.current.screenWidthDp >= 700
    val context = LocalContext.current
    val editionSideRailOpen = isTvEdition && isEditionSideRailOpen()
'''
if state_anchor not in main:
    raise SystemExit("MainScreen state anchor not found")
main = main.replace(state_anchor, state_replacement, 1)

back_anchor = '''    BackHandler(
        enabled = isTvEdition &&
            pendingTransfer == null &&
            searchPane != activePane &&
            selectionCount == 0,
    ) {
        val controller = vm.activeCtrl
        val paneState = controller.state.value
        val focusedId = paneState.focusedDirId
        val focusedIndex = focusedId?.let { id ->
            paneState.nodes.indexOfLast { node -> node.entry.id == id }
        } ?: -1
        val focusedNode = paneState.nodes.getOrNull(focusedIndex)
        val parentNode = if (focusedNode != null && focusedNode.depth > 0) {
            paneState.nodes
                .take(focusedIndex)
                .lastOrNull { node -> node.depth == focusedNode.depth - 1 }
        } else {
            null
        }

        if (focusedNode != null && parentNode != null) {
            if (focusedNode.entry.isContainer) controller.collapse(focusedNode.entry)
            controller.revealPath(parentNode.entry.id, animate = false)
            lastTvRootBackAt = 0L
        } else {
            val now = SystemClock.elapsedRealtime()
            if (now - lastTvRootBackAt <= TvExitBackWindowMs) {
                (context as? Activity)?.finish()
            } else {
                lastTvRootBackAt = now
                vm.snackbar.tryEmit(context.getString(R.string.press_back_again_to_exit))
            }
        }
    }

'''
back_replacement = back_anchor + '''    // TV Back closes an open side rail before any browser/root Back behavior can run.
    BackHandler(enabled = editionSideRailOpen) {
        dismissEditionSideRail()
        lastTvRootBackAt = 0L
    }

'''
if back_anchor not in main:
    raise SystemExit("TV root BackHandler block not found")
main = main.replace(back_anchor, back_replacement, 1)
main_path.write_text(main)
