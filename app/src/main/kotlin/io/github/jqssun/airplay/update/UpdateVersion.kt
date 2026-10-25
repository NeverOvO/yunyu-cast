package io.github.jqssun.airplay.update

/** Semantic-version ordering, including preview/rc tags; numeric parts never overflow. */
class UpdateVersion private constructor(
    val value: String,
    private val core: List<String>,
    private val prerelease: List<String>,
) : Comparable<UpdateVersion> {
    override fun compareTo(other: UpdateVersion): Int {
        core.indices.forEach { index ->
            compareNumber(core[index], other.core[index]).takeIf { it != 0 }?.let { return it }
        }
        if (prerelease.isEmpty()) return if (other.prerelease.isEmpty()) 0 else 1
        if (other.prerelease.isEmpty()) return -1
        for (index in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val left = prerelease[index]
            val right = other.prerelease[index]
            val leftNumber = left.all { it in '0'..'9' }
            val rightNumber = right.all { it in '0'..'9' }
            val result = when {
                leftNumber && rightNumber -> compareNumber(left, right)
                leftNumber -> -1
                rightNumber -> 1
                else -> left.compareTo(right)
            }
            if (result != 0) return result
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    override fun toString(): String = value

    companion object {
        private val pattern = Regex(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
                "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$"
        )

        fun parse(value: String): UpdateVersion? {
            if (value.isEmpty() || value.length > 200) return null
            val normalized = value.removePrefix("v")
            val match = pattern.matchEntire(normalized) ?: return null
            val prerelease = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (prerelease.any { identifier ->
                    identifier.length > 1 && identifier[0] == '0' && identifier.all { it in '0'..'9' }
                }) return null
            return UpdateVersion(normalized, (1..3).map { match.groupValues[it] }, prerelease)
        }

        private fun compareNumber(left: String, right: String): Int =
            left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)
    }
}
