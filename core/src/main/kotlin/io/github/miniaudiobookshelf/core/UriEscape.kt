package io.github.miniaudiobookshelf.core

object UriEscape {
    fun escape(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size)
        for (b in bytes) {
            val u = b.toInt() and 0xFF
            if (isUnreserved(u)) {
                out.append(u.toChar())
            } else {
                out.append('%')
                out.append("0123456789ABCDEF"[u shr 4])
                out.append("0123456789ABCDEF"[u and 0x0F])
            }
        }
        return out.toString()
    }

    private fun isUnreserved(u: Int): Boolean {
        val c = u.toChar()
        return c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~'
    }
}
