package io.github.miniaudiobookshelf.core

object ServerVersion {
    const val MINIMUM = "2.26.0"

    fun compare(left: String, right: String): Int {
        val a = parse(left)
        val b = parse(right)
        for (i in 0 until 3) {
            if (a[i] < b[i]) return -1
            if (a[i] > b[i]) return 1
        }
        return 0
    }

    fun isSupported(serverVersion: String): Boolean {
        return compare(serverVersion, MINIMUM) >= 0
    }

    // GLib int.parse on each dotted part: leading digits only, junk becomes 0.
    // "2.26.0-beta" therefore compares equal to "2.26.0".
    private fun parse(version: String): IntArray {
        val parts = version.split('.')
        val numbers = IntArray(3)
        for (i in 0 until 3) {
            numbers[i] = if (i < parts.size) leadingInt(parts[i]) else 0
        }
        return numbers
    }

    private fun leadingInt(part: String): Int {
        val digits = part.takeWhile { it.isDigit() }
        return digits.toIntOrNull() ?: 0
    }
}
