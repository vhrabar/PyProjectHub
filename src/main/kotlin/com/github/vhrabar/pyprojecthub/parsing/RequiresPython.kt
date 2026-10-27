package com.github.vhrabar.pyprojecthub.parsing

/**
 * The subset of PEP 440 version specifiers needed to answer "does this project support Python 3.X?",
 * plus Poetry's `^`, `~` and `||` constraint syntax. Pre-release, post-release and local version
 * suffixes are ignored, because the question is only asked for release versions.
 */
class RequiresPython private constructor(
    private val alternatives: List<List<Clause>>,
) {
    /** True if any 3.X.micro release satisfies the specifier. */
    fun allowsMinor(
        major: Int,
        minor: Int,
    ): Boolean =
        (0..MAX_MICRO).any { micro ->
            val candidate = listOf(major, minor, micro)
            alternatives.any { clauses -> clauses.all { it.matches(candidate) } }
        }

    private data class Clause(
        val op: String,
        val version: List<Int>,
        val wildcard: Boolean,
    ) {
        fun matches(candidate: List<Int>): Boolean =
            when (op) {
                "==", "===" -> if (wildcard) hasPrefix(candidate, version) else compare(candidate, version) == 0
                "!=" -> if (wildcard) !hasPrefix(candidate, version) else compare(candidate, version) != 0
                ">=" -> compare(candidate, version) >= 0
                "<=" -> compare(candidate, version) <= 0
                ">" -> compare(candidate, version) > 0
                "<" -> compare(candidate, version) < 0
                "~=" -> compare(candidate, version) >= 0 && hasPrefix(candidate, version.dropLast(1))
                else -> false
            }
    }

    companion object {
        private const val MAX_MICRO = 50
        private val CLAUSE = Regex("""^(~=|===|==|!=|<=|>=|<|>|\^|~)?\s*v?(\d+(?:\.\d+)*)(\.\*)?[A-Za-z0-9.+_-]*$""")

        /** Returns null when the specifier can't be understood, so callers can treat it as unknown. */
        fun parse(spec: String): RequiresPython? {
            if (spec.isBlank()) return RequiresPython(listOf(emptyList()))
            val alternatives =
                spec.split("||").map { alternative ->
                    alternative
                        .split(',')
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .flatMap { parseClause(it) ?: return null }
                }
            return RequiresPython(alternatives)
        }

        private fun parseClause(text: String): List<Clause>? {
            val match = CLAUSE.matchEntire(text.replace(" ", "")) ?: return null
            val op = match.groupValues[1]
            val version = match.groupValues[2].split('.').map { it.toIntOrNull() ?: return null }
            val wildcard = match.groupValues[3].isNotEmpty()
            return when (op) {
                // Poetry caret: bump the left-most non-zero component
                "^" -> {
                    val index = version.indexOfFirst { it != 0 }.let { if (it == -1) version.lastIndex else it }
                    listOf(Clause(">=", version, false), Clause("<", bump(version, index), false))
                }

                // Poetry tilde: ~3 means >=3,<4; ~3.10 means >=3.10,<3.11
                "~" -> {
                    listOf(Clause(">=", version, false), Clause("<", bump(version, minOf(1, version.lastIndex)), false))
                }

                "~=" -> {
                    if (version.size < 2) null else listOf(Clause("~=", version, false))
                }

                // A bare version (Poetry style) means an exact match, or a prefix match with ".*"
                "" -> {
                    listOf(Clause("==", version, wildcard))
                }

                else -> {
                    listOf(Clause(op, version, wildcard))
                }
            }
        }

        private fun bump(
            version: List<Int>,
            index: Int,
        ): List<Int> = version.take(index) + (version[index] + 1)

        private fun compare(
            a: List<Int>,
            b: List<Int>,
        ): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val diff = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
                if (diff != 0) return diff
            }
            return 0
        }

        private fun hasPrefix(
            candidate: List<Int>,
            prefix: List<Int>,
        ): Boolean = prefix.indices.all { candidate.getOrElse(it) { 0 } == prefix[it] }
    }
}
