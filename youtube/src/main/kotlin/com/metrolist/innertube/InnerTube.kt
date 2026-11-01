package com.metrolist.innertube

import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.innertubex.InnerTube as InnerTubeX
import com.metrolist.innertubex.InnerTubeHttpException
import roma.core.HttpResponse

/**
 * Compatibility facade that keeps Metrolist's parsed response models while InnerTubeX owns
 * YouTube request construction, session handling, retries, and authenticated mutations.
 */
class InnerTube {
    // Built on first use, not here: roma sets the session on the main thread at start, and loading InnerTubeX there
    // would delay it. Settings made before then wait in [pending] and are applied when it's built.
    @Volatile private var built: InnerTubeX? = null
    private val pending = ArrayList<(InnerTubeX) -> Unit>()
    private val innerTubeX: InnerTubeX
        get() = built ?: build()

    @Synchronized
    private fun build(): InnerTubeX {
        built?.let { return it }
        return InnerTubeX().also { x -> pending.forEach { it(x) }; pending.clear(); built = x }
    }

    @Synchronized
    private fun configure(f: (InnerTubeX) -> Unit) { built?.let(f) ?: pending.add(f) }

    var locale: YouTubeLocale
        get() = innerTubeX.locale
        set(value) {
            configure { it.locale = value }
        }

    var visitorData: String?
        get() = innerTubeX.visitorData
        set(value) {
            configure { it.visitorData = value }
        }

    var dataSyncId: String?
        get() = innerTubeX.dataSyncId
        set(value) {
            configure { it.dataSyncId = value }
        }

    var authUser: String
        get() = innerTubeX.authUser
        set(value) {
            configure { it.authUser = value }
        }

    var cookie: String?
        get() = innerTubeX.cookie
        set(value) {
            configure { it.cookie = value }
        }

    var useLoginForBrowse: Boolean
        get() = innerTubeX.useLoginForBrowse
        set(value) {
            configure { it.useLoginForBrowse = value }
        }

    /** The InnerTubeX instance the stream fallback shares, with this session. */
    fun extractionTransport(): InnerTubeX = innerTubeX

    suspend fun search(
        client: YouTubeClient,
        query: String? = null,
        params: String? = null,
        continuation: String? = null,
        setLogin: Boolean = false,
    ) = innerTubeX.search(client, query, params, continuation, setLogin = setLogin)

    suspend fun player(
        client: YouTubeClient,
        videoId: String,
        playlistId: String?,
        signatureTimestamp: Int?,
        poToken: String? = null,
    ) = innerTubeX.player(client, videoId, playlistId, signatureTimestamp, poToken)

    suspend fun browse(
        client: YouTubeClient,
        browseId: String? = null,
        params: String? = null,
        continuation: String? = null,
        setLogin: Boolean = false,
    ) = innerTubeX.browse(client, browseId, params, continuation, setLogin)

    suspend fun next(
        client: YouTubeClient,
        videoId: String?,
        playlistId: String?,
        playlistSetVideoId: String?,
        index: Int?,
        params: String?,
        continuation: String? = null,
    ) = innerTubeX.next(client, videoId, playlistId, playlistSetVideoId, index, params, continuation)

    suspend fun getSearchSuggestions(
        client: YouTubeClient,
        input: String,
    ) = innerTubeX.getSearchSuggestions(client, input, setLogin = false)

    suspend fun fetchFreshVisitorData() = innerTubeX.fetchFreshVisitorData()

    suspend fun likeVideo(
        client: YouTubeClient,
        videoId: String,
    ) = innerTubeX.likeVideo(client, videoId).requireSuccess("likeVideo")

    suspend fun unlikeVideo(
        client: YouTubeClient,
        videoId: String,
    ) = innerTubeX.unlikeVideo(client, videoId).requireSuccess("unlikeVideo")

    suspend fun likePlaylist(client: YouTubeClient, playlistId: String, like: Boolean) =
        innerTubeX.likePlaylist(client, playlistId, like).requireSuccess("likePlaylist")

    suspend fun subscribe(client: YouTubeClient, channelId: String, subscribe: Boolean) =
        innerTubeX.subscribe(client, channelId, subscribe).requireSuccess("subscribe")

    private fun HttpResponse.requireSuccess(operation: String): HttpResponse {
        if (!ok) throw InnerTubeHttpException(operation, status)
        return this
    }
}
