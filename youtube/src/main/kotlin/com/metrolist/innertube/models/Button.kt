package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class Button(
    val buttonRenderer: ButtonRenderer,
) {
    @Serializable
    class ButtonRenderer(
        val navigationEndpoint: NavigationEndpoint?,
    )
}
