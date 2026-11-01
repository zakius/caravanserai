package com.metrolist.innertube.models.response
 
 import com.metrolist.innertube.models.MusicShelfRenderer
 import kotlinx.serialization.Serializable
 
 @Serializable
 class ContinuationResponse(
     val onResponseReceivedActions: List<ResponseAction>?,
 ) {
 
     @Serializable
     class ResponseAction(
         val appendContinuationItemsAction: ContinuationItems?,
     )
 
     @Serializable
     class ContinuationItems(
         val continuationItems: List<MusicShelfRenderer.Content>?,
     )
 }
