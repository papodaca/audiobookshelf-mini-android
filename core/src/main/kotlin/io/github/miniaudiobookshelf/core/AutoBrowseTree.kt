package io.github.miniaudiobookshelf.core

sealed class AutoMediaId {
    data object Root : AutoMediaId()
    data object Continue : AutoMediaId()
    data object Downloads : AutoMediaId()
    data object Libraries : AutoMediaId()
    data object SignIn : AutoMediaId()
    data class Library(val libraryId: String) : AutoMediaId()
    data class Item(val itemId: String, val episodeId: String? = null) : AutoMediaId()

    fun encode(): String = when (this) {
        Root -> ROOT
        Continue -> CONTINUE
        Downloads -> DOWNLOADS
        Libraries -> LIBRARIES
        SignIn -> SIGN_IN
        is Library -> "$LIBRARIES/$libraryId"
        is Item -> {
            val episode = episodeId
            if (episode.isNullOrEmpty()) "$ITEM_PREFIX$itemId" else "$ITEM_PREFIX$itemId$EPISODE_SEP$episode"
        }
    }

    fun playbackUri(): String = "abs://${encode()}"

    companion object {
        const val ROOT = "root"
        const val CONTINUE = "root/continue"
        const val DOWNLOADS = "root/downloads"
        const val LIBRARIES = "root/libraries"
        const val SIGN_IN = "root/signin"
        const val ITEM_PREFIX = "item/"
        const val EPISODE_SEP = "/ep/"

        fun parse(raw: String): AutoMediaId? {
            if (raw.isEmpty() || looksLikeSession(raw)) return null
            return when {
                raw == ROOT -> Root
                raw == CONTINUE -> Continue
                raw == DOWNLOADS -> Downloads
                raw == LIBRARIES -> Libraries
                raw == SIGN_IN -> SignIn
                raw.startsWith("$LIBRARIES/") -> {
                    val id = raw.removePrefix("$LIBRARIES/")
                    if (id.isEmpty() || looksLikeSession(id)) null else Library(id)
                }
                raw.startsWith(ITEM_PREFIX) -> {
                    val rest = raw.removePrefix(ITEM_PREFIX)
                    if (rest.isEmpty() || looksLikeSession(rest)) return null
                    val parts = rest.split(EPISODE_SEP, limit = 2)
                    val itemId = parts[0]
                    if (itemId.isEmpty()) return null
                    Item(itemId, parts.getOrNull(1)?.ifEmpty { null })
                }
                else -> null
            }
        }

        fun parseParent(raw: String): AutoMediaId = parse(raw) ?: Root

        fun looksLikeSession(raw: String): Boolean {
            return raw.contains("/public/session/") || raw.contains("/track/")
        }
    }
}

data class AutoNode(
    val id: AutoMediaId,
    val title: String,
    val subtitle: String = "",
    val browsable: Boolean,
    val playable: Boolean,
)

object AutoBrowseTree {
    fun rootItem(): AutoNode = AutoNode(
        id = AutoMediaId.Root,
        title = "Mini Audiobookshelf",
        browsable = true,
        playable = false,
    )

    fun rootTabs(selected: Library? = null): List<AutoNode> {
        val continueTitle = if (ViewLogic.isPodcastLibrary(selected)) "Episodes" else "Continue"
        return listOf(
            AutoNode(AutoMediaId.Continue, continueTitle, browsable = true, playable = false),
            AutoNode(AutoMediaId.Downloads, "Downloads", browsable = true, playable = false),
            AutoNode(AutoMediaId.Libraries, "Libraries", browsable = true, playable = false),
        )
    }

    fun notice(title: String): List<AutoNode> = listOf(
        AutoNode(AutoMediaId.SignIn, title, browsable = false, playable = true),
    )

    fun continueChildren(items: List<LibraryItem>): List<AutoNode> {
        return items.mapNotNull { itemNode(it) }
    }

    fun downloadChildren(entries: List<DownloadEntry>): List<AutoNode> {
        return entries.filter { it.complete }.map { entry ->
            AutoNode(
                id = AutoMediaId.Item(entry.itemId, entry.episodeId.ifEmpty { null }),
                title = entry.title,
                subtitle = entry.author,
                browsable = false,
                playable = true,
            )
        }
    }

    fun libraryChildren(libraries: List<Library>): List<AutoNode> {
        return libraries.map { library ->
            AutoNode(
                id = AutoMediaId.Library(library.id),
                title = library.name,
                subtitle = if (ViewLogic.isPodcastLibrary(library)) "Podcasts" else "Books",
                browsable = true,
                playable = false,
            )
        }
    }

    fun itemNode(item: LibraryItem): AutoNode? {
        if (ViewLogic.playDecision(item) == ViewLogic.PlayDecision.NOT_PLAYABLE) return null
        val episode = item.episodeId.ifEmpty { null }
        val title = if (episode != null) ViewLogic.episodeLabel(item) else item.title
        val subtitle = if (episode != null) {
            item.author.ifEmpty { ViewLogic.progressSummary(item).orEmpty() }
        } else {
            item.author
        }
        return AutoNode(
            id = AutoMediaId.Item(item.id, episode),
            title = title,
            subtitle = subtitle,
            browsable = false,
            playable = true,
        )
    }

    fun pickSearchPlayable(hits: List<LibraryItem>): LibraryItem? {
        return hits.firstOrNull { ViewLogic.playDecision(it) != ViewLogic.PlayDecision.NOT_PLAYABLE }
            ?: hits.firstOrNull()
    }
}
