package com.comics8.core.source.hitomi

data class GgJsRouter(
    val b: String,
    private val members: Set<Int>,
    private val memberM: Int = 1,
    private val defaultM: Int = 0,
) {
    fun m(g: Int): Int = if (g in members) memberM else defaultM

    fun subdomainIndex(hash: String): Int = m(gFromHash(hash)) + 1

    companion object {
        private val bRegex = Regex("""\bb\s*:\s*["']([^"']*)["']""")
        private val caseRegex = Regex("""case\s+(\d+)\s*:""")
        private val arrayRegex = Regex("""(?:var\s+)?o\s*=\s*\[([0-9,\s]+)\]""")
        private val caseReturnRegex = Regex("""case\s+\d+\s*:[\s\S]*?return\s+(\d+)""")
        private val caseAssignRegex = Regex("""case\s+\d+\s*:\s*[A-Za-z_]\w*\s*=\s*(\d+)""")
        private val defaultReturnRegex = Regex("""default\s*:\s*return\s+(\d+)""")
        private val trailingReturnRegex = Regex("""\}\s*return\s+(\d+)\s*;?\s*\}""")
        private val defaultInitRegex = Regex(
            """(?:(?:var|let|const)\s+)?[A-Za-z_]\w*\s*=\s*(\d+)\s*;\s*switch""",
        )

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

        /** Live `gg.s(h)`: last three hex digits as decimal, not a folder path. */
        fun sFromHash(hash: String): String = gFromHash(hash).toString()

        fun parse(js: String): GgJsRouter {
            val b = bRegex.find(js)?.groupValues?.get(1).orEmpty()
            val cases = caseRegex.findAll(js).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()
            val fromArray = arrayRegex.find(js)?.groupValues?.get(1)
                ?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.toSet()
                .orEmpty()
            val members = cases + fromArray
            val memberM = caseReturnRegex.find(js)?.groupValues?.get(1)?.toIntOrNull()
                ?: caseAssignRegex.find(js)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            val defaultM = defaultReturnRegex.find(js)?.groupValues?.get(1)?.toIntOrNull()
                ?: trailingReturnRegex.find(js)?.groupValues?.get(1)?.toIntOrNull()
                ?: defaultInitRegex.find(js)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0
            return GgJsRouter(b = b, members = members, memberM = memberM, defaultM = defaultM)
        }
    }
}
