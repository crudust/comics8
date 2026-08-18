package com.comics8.core.source

data class HttpResult(
    val code: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun totalLength(): Long? {
        val contentRange = header("Content-Range")
        if (!contentRange.isNullOrBlank()) {
            val slash = contentRange.lastIndexOf('/')
            if (slash >= 0 && slash < contentRange.length - 1) {
                val total = contentRange.substring(slash + 1).trim()
                if (total != "*") {
                    total.toLongOrNull()?.let { return it }
                }
            }
        }
        return header("Content-Length")?.trim()?.toLongOrNull()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpResult) return false
        return code == other.code && headers == other.headers && body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = code
        result = 31 * result + headers.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }
}
