from pathlib import Path

path = Path("app/src/tv/java/app/local1st/files/ui/browser/PaneView.kt")
text = path.read_text()

text = text.replace("import android.content.pm.PackageManager\n", "")
text = text.replace(
    """    val context = LocalContext.current
    val isTv = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
""",
    """    val context = LocalContext.current
    // TV source-set code must always use TV D-pad behavior. Some Google TV devices do not
    // advertise FEATURE_LEANBACK consistently, which previously disabled vertical navigation.
    val isTv = true
""",
)

path.write_text(text)
