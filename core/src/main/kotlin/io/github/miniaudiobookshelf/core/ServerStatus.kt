package io.github.miniaudiobookshelf.core

data class ServerStatus(
    val version: String = "",
    val authMethods: List<String> = emptyList(),
) {
    val localAuth: Boolean get() = authMethods.contains("local")
}
