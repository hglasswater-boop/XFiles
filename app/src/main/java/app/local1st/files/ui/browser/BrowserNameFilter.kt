package app.local1st.files.ui.browser

/** Filename matcher used by the in-place browser filter. */
internal fun browserNameMatches(name: String, rawQuery: String): Boolean {
    val query = rawQuery.trim()
    if (query.isEmpty()) return true
    if ('*' !in query && '?' !in query) {
        return name.contains(query, ignoreCase = true)
    }

    val pattern = StringBuilder(query.length + 8)
    for (c in query) {
        when (c) {
            '*' -> pattern.append(".*")
            '?' -> pattern.append('.')
            in REGEX_METACHARS -> pattern.append('\\').append(c)
            else -> pattern.append(c)
        }
    }
    return Regex(pattern.toString(), RegexOption.IGNORE_CASE).matches(name)
}

private const val REGEX_METACHARS = "\\^$.|+()[]{}"
