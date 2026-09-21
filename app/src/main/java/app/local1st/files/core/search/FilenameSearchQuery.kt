package app.local1st.files.core.search

/**
 * Shared filename-search query semantics used by both the UI and search engine.
 *
 * - Plain text is matched as a case-insensitive substring.
 * - A leading-dot query such as `.mp4` is treated as an extension suffix.
 * - When `*` or `?` is present, the query is treated as a whole-filename glob where
 *   `*` matches zero or more characters and `?` matches exactly one character.
 */
internal object FilenameSearchQuery {
    const val MIN_LITERAL_LENGTH = 2

    /**
     * Plain one-character searches stay disabled to avoid expensive broad walks, while
     * one-character wildcard queries are meaningful and therefore allowed.
     */
    fun isSearchable(query: String): Boolean =
        query.isNotEmpty() &&
            (query.length >= MIN_LITERAL_LENGTH || query.any(::isWildcard))

    fun matcher(query: String): (String) -> Boolean {
        if (!query.any(::isWildcard)) {
            if (query.startsWith('.') && query.length > 1) {
                return { name -> name.endsWith(query, ignoreCase = true) }
            }
            return { name -> name.contains(query, ignoreCase = true) }
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
        val regex = Regex(pattern.toString(), RegexOption.IGNORE_CASE)
        return { name -> regex.matches(name) }
    }

    private fun isWildcard(c: Char): Boolean = c == '*' || c == '?'

    private const val REGEX_METACHARS = "\\^$.|+()[]{}"
}
