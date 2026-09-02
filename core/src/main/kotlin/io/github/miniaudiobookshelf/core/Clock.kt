package io.github.miniaudiobookshelf.core

interface Clock {
    fun unixSeconds(): Long
}

class SystemClock : Clock {
    override fun unixSeconds(): Long = System.currentTimeMillis() / 1000
}
