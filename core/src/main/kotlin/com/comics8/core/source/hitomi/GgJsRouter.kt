package com.comics8.core.source.hitomi

data class GgJsRouter(
    val b: String,
    private val ones: Set<Int>,
    private val defaultM: Int = 0,
) {
    fun m(g: Int): Int = if (g in ones) 1 else defaultM

    fun subdomainIndex(hash: String): Int = m(gFromHash(hash)) + 1

    companion object {
        private val bRegex = Regex("""\bb\s*:\s*["']([^"']*)["']""")
        private val caseRegex = Regex("""case\s+(\d+)\s*:""")
        private val arrayRegex = Regex("""(?:var\s+)?o\s*=\s*\[([0-9,\s]+)\]""")

        fun pathFromHash(hash: String): String {
            val last3 = hash.takeLast(3)
            check(last3.length == 3) { "hash too short for path" }
            return "${last3[2]}/${last3[0]}${last3[1]}"
        }

        fun gFromHash(hash: String): Int {
            val last3 = hash.takeLast(3)
            if (last3.length < 3) return 0
            return "${last3[2]}${last3[0]}${last3[1]}".toIntOrNull(16) ?: 0
        }

        fun parse(js: String): GgJsRouter {
            val b = bRegex.find(js)?.groupValues?.get(1).orEmpty()
            val cases = caseRegex.findAll(js).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()
            val fromArray = arrayRegex.find(js)?.groupValues?.get(1)
                ?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.toSet()
                .orEmpty()
            return GgJsRouter(b = b, ones = cases + fromArray)
        }
    }
}
