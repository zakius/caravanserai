package com.metrolist.innertube.models.response

import kotlinx.serialization.Serializable

/**
 * Only the like button of a `next` response, which is where YouTube Music tells the signed-in user whether they like
 * the song (roma's Like controls). The rest of the response (queue, tabs, menus) is skipped unread.
 */
@Serializable
class LikeStatusResponse(val playerOverlays: PlayerOverlays? = null) {
    @Serializable class PlayerOverlays(val playerOverlayRenderer: Renderer? = null)
    @Serializable class Renderer(val actions: List<Action>? = null)
    @Serializable class Action(val likeButtonRenderer: LikeButton? = null)
    @Serializable class LikeButton(val target: Target? = null, val likeStatus: String? = null)
    @Serializable class Target(val videoId: String? = null)

    /** True for liked, false for not (or disliked), null when the response has no like button for this video. */
    fun liked(videoId: String): Boolean? =
        playerOverlays?.playerOverlayRenderer?.actions.orEmpty().firstNotNullOfOrNull { it.likeButtonRenderer }
            ?.takeIf { it.target?.videoId == videoId }?.likeStatus?.let { it == "LIKE" }
}
