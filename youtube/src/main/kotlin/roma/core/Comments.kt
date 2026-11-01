package roma.core

import com.metrolist.innertube.YouTube
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One YouTube comment. [text] is HTML (line breaks), as the UI reads it. [thread] fetches the first page of its
 * replies, null when it has none; YouTube nests replies one level only, so a reply's own [thread] is null.
 */
class Comment(val author: String, val text: String, val likes: String, val ago: String, val replies: Int, val pinned: Boolean, val thread: (() -> CommentPage)?)

/** A page of a song's YouTube comments; [next] fetches the following one, null at the end. */
class CommentPage(val comments: List<Comment>, val disabled: Boolean, val next: (() -> CommentPage)?)

/**
 * Through the WEB client's `next`: the watch data names the comments section's first continuation, and each page names
 * the next one and each thread's replies. The comments themselves are entities in frameworkUpdates, found by the key
 * each item carries. Blocking: call off the main thread.
 */
object Comments {
    fun of(videoId: String): CommentPage {
        val section = find(next(videoId, null)) { it["targetId"].str == "comments-section" }
        return section?.let(::token)?.let(::page) ?: CommentPage(emptyList(), true, null)
    }

    private fun next(videoId: String?, continuation: String?) = runBlocking { YouTube.nextJson(videoId, continuation).getOrThrow() }

    private fun page(token: String): CommentPage {
        val r = next(null, token)
        val entities = HashMap<String, JsonObject>()
        for (m in r.o("frameworkUpdates")?.o("entityBatchUpdate")?.a("mutations").orEmpty()) {
            val e = (m as? JsonObject)?.o("payload")?.o("commentEntityPayload") ?: continue
            entities[e["key"].str ?: continue] = e
        }
        val items = r.a("onResponseReceivedEndpoints").orEmpty().flatMap { end ->
            (end as JsonObject).values.mapNotNull { (it as? JsonObject)?.a("continuationItems") }.flatten()
        }
        var more: String? = null
        val comments = items.mapNotNull { item ->
            item as JsonObject
            item.o("continuationItemRenderer")?.let { more = token(it); return@mapNotNull null }
            val thread = item.o("commentThreadRenderer")
            val vm = (thread ?: item).o("commentViewModel")?.let { it.o("commentViewModel") ?: it } ?: return@mapNotNull null
            val e = entities[vm["commentKey"].str] ?: return@mapNotNull null
            val toolbar = e.o("toolbar")
            val replies = thread?.o("replies")?.let(::token)
            Comment(
                e.o("author")?.get("displayName").str.orEmpty(),
                html(e.o("properties")?.o("content")?.get("content").str.orEmpty()),
                toolbar?.get("likeCountNotliked").str.orEmpty().trim(),
                e.o("properties")?.get("publishedTime").str.orEmpty(),
                count(toolbar?.get("replyCount").str),
                vm.containsKey("pinnedText"),
                replies?.let { t -> { page(t) } },
            )
        }
        return CommentPage(comments, false, more?.let { t -> { page(t) } })
    }

    private fun html(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")

    // "629", "1.2K": the UI shows the number of replies.
    internal fun count(s: String?): Int {
        val t = s?.trim()?.uppercase().orEmpty()
        val n = t.trimEnd('K', 'M').toDoubleOrNull() ?: return 0
        return (n * when (t.lastOrNull()) { 'K' -> 1_000; 'M' -> 1_000_000; else -> 1 }).toInt()
    }

    /** The first continuation token under [e]. */
    private fun token(e: JsonElement): String? = find(e) { it.containsKey("continuationCommand") }?.o("continuationCommand")?.get("token").str

    private fun find(e: JsonElement, match: (JsonObject) -> Boolean): JsonObject? = when (e) {
        is JsonObject -> if (match(e)) e else e.values.firstNotNullOfOrNull { find(it, match) }
        is JsonArray -> e.firstNotNullOfOrNull { find(it, match) }
        else -> null
    }

    private fun JsonObject.o(k: String) = get(k) as? JsonObject
    private fun JsonObject.a(k: String) = get(k) as? JsonArray
    private val JsonElement?.str get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
