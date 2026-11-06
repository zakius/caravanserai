package roma.core

import com.metrolist.innertube.YouTube
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One YouTube comment. [text] is HTML, with line breaks as `<br>`, because the UI renders it as HTML. [thread] fetches
 * the first page of its replies and is null when it has none. YouTube nests replies one level only, so a reply's own
 * [thread] is null.
 */
class Comment(
    val author: String,
    val text: String,
    val likes: String,
    val ago: String,
    val replies: Int,
    val pinned: Boolean,
    val thread: (() -> CommentPage)?,
)

/** A page of a song's YouTube comments. [next] fetches the following page and is null at the end. */
class CommentPage(val comments: List<Comment>, val disabled: Boolean, val next: (() -> CommentPage)?)

/**
 * Reads comments through the WEB client's `next` endpoint. The watch data names the first continuation of the comments
 * section, and each page names the next page and each thread's replies. The comment contents are entities in
 * frameworkUpdates, found by the key each item carries. Blocking, so call it off the main thread.
 */
object Comments {
    fun of(videoId: String): CommentPage {
        val section = find(next(videoId, null)) { it["targetId"].str == "comments-section" }
        if (section == null) return CommentPage(emptyList(), true, null)
        val firstPage = token(section) ?: return CommentPage(emptyList(), true, null)
        return page(firstPage)
    }

    private fun next(videoId: String?, continuation: String?) = runBlocking {
        YouTube.nextJson(videoId, continuation).getOrThrow()
    }

    private fun page(token: String): CommentPage {
        val r = next(null, token)
        val entities = HashMap<String, JsonObject>()
        val mutations = r.o("frameworkUpdates")?.o("entityBatchUpdate")?.a("mutations").orEmpty()
        for (m in mutations) {
            val e = (m as? JsonObject)?.o("payload")?.o("commentEntityPayload") ?: continue
            val key = e["key"].str ?: continue
            entities[key] = e
        }
        val items = r.a("onResponseReceivedEndpoints").orEmpty().flatMap { end ->
            val lists = (end as JsonObject).values.mapNotNull { value -> (value as? JsonObject)?.a("continuationItems") }
            lists.flatten()
        }
        var more: String? = null
        val comments = items.mapNotNull { item ->
            item as JsonObject
            val continuation = item.o("continuationItemRenderer")
            if (continuation != null) {
                more = token(continuation)
                return@mapNotNull null
            }
            val thread = item.o("commentThreadRenderer")
            // Some items wrap the view model in a second commentViewModel key.
            val outer = (thread ?: item).o("commentViewModel") ?: return@mapNotNull null
            val vm = outer.o("commentViewModel") ?: outer
            val e = entities[vm["commentKey"].str] ?: return@mapNotNull null
            val toolbar = e.o("toolbar")
            val properties = e.o("properties")
            val replies = thread?.o("replies")?.let(::token)
            val openThread: (() -> CommentPage)? = if (replies == null) null else ({ page(replies) })
            Comment(
                e.o("author")?.get("displayName").str.orEmpty(),
                html(properties?.o("content")?.get("content").str.orEmpty()),
                toolbar?.get("likeCountNotliked").str.orEmpty().trim(),
                properties?.get("publishedTime").str.orEmpty(),
                count(toolbar?.get("replyCount").str),
                vm.containsKey("pinnedText"),
                openThread,
            )
        }
        val nextToken = more
        val nextPage: (() -> CommentPage)? = if (nextToken == null) null else ({ page(nextToken) })
        return CommentPage(comments, false, nextPage)
    }

    private fun html(text: String) = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\n", "<br>")

    /** Parses YouTube's abbreviated reply count, such as "629" or "1.2K". */
    internal fun count(s: String?): Int {
        val t = s?.trim()?.uppercase().orEmpty()
        val n = t.trimEnd('K', 'M').toDoubleOrNull() ?: return 0
        val multiplier = when (t.lastOrNull()) {
            'K' -> 1_000
            'M' -> 1_000_000
            else -> 1
        }
        return (n * multiplier).toInt()
    }

    /** The first continuation token under [e]. */
    private fun token(e: JsonElement): String? {
        val holder = find(e) { it.containsKey("continuationCommand") } ?: return null
        return holder.o("continuationCommand")?.get("token").str
    }

    private fun find(e: JsonElement, match: (JsonObject) -> Boolean): JsonObject? = when (e) {
        is JsonObject -> if (match(e)) e else e.values.firstNotNullOfOrNull { find(it, match) }
        is JsonArray -> e.firstNotNullOfOrNull { find(it, match) }
        else -> null
    }

    private fun JsonObject.o(k: String) = get(k) as? JsonObject
    private fun JsonObject.a(k: String) = get(k) as? JsonArray

    private val JsonElement?.str: String?
        get() {
            val primitive = this as? JsonPrimitive ?: return null
            return if (primitive.isString) primitive.content else null
        }
}
