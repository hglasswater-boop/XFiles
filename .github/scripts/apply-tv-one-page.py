from pathlib import Path

path = Path("app/src/main/java/app/local1st/files/ui/main/MainScreen.kt")
text = path.read_text()

repls = [
(
"""                val pagerState = rememberPagerState(initialPage = activePane) { 2 }
                LaunchedEffect(pagerState, sessionReady) {
                    if (!sessionReady) return@LaunchedEffect
                    snapshotFlow { pagerState.currentPage }.collect { vm.setActivePane(it) }
                }
                LaunchedEffect(activePane) {
""",
"""                val tvPaneIndex = activePane
                val pagerState = rememberPagerState(
                    initialPage = if (isTvEdition) 0 else activePane,
                ) { if (isTvEdition) 1 else 2 }
                LaunchedEffect(pagerState, sessionReady) {
                    if (!sessionReady || isTvEdition) return@LaunchedEffect
                    snapshotFlow { pagerState.currentPage }.collect { vm.setActivePane(it) }
                }
                LaunchedEffect(activePane) {
                    if (isTvEdition) return@LaunchedEffect
"""
),
("""                ) { page ->
                    val pane = vm.panes[page]
""", """                ) { page ->
                    val paneIndex = if (isTvEdition) tvPaneIndex else page
                    val pane = vm.panes[paneIndex]
"""),
("active = activePane == page,", "active = activePane == paneIndex,"),
("onActivate = { vm.setActivePane(page) },", "onActivate = { vm.setActivePane(paneIndex) },"),
("initiallyLaidOutPanes = initiallyLaidOutPanes + page", "initiallyLaidOutPanes = initiallyLaidOutPanes + paneIndex"),
("vm.onPaneInitialLayoutReady(page, version)", "vm.onPaneInitialLayoutReady(paneIndex, version)"),
("if (page == activePane) startupContentReady = true", "if (paneIndex == activePane) startupContentReady = true"),
("breadcrumbAlignment = if (page == 0) {", "breadcrumbAlignment = if (isTvEdition || paneIndex == 0) {"),
("searchActive = searchPane == page,", "searchActive = searchPane == paneIndex,"),
("onSearchClose = { closeSearch(page) },", "onSearchClose = { closeSearch(paneIndex) },"),
("if (searchPane == page) {", "if (searchPane == paneIndex) {"),
("if (isTvEdition && searchPane != page) {", "if (isTvEdition && searchPane != paneIndex) {"),
]

for old, new in repls:
    if old not in text:
        raise SystemExit(f"anchor not found: {old[:80]!r}")
    text = text.replace(old, new, 1)

path.write_text(text)
