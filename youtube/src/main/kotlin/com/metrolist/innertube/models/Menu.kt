package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class Menu(
    val menuRenderer: MenuRenderer,
) {
    @Serializable
    class MenuRenderer(
        val items: List<Item>?,
        val topLevelButtons: List<TopLevelButton>?,
    ) {
        @Serializable
        class Item(
            val menuNavigationItemRenderer: MenuNavigationItemRenderer?,
        ) {
            @Serializable
            class MenuNavigationItemRenderer(
                val icon: Icon,
                val navigationEndpoint: NavigationEndpoint,
            )
        }

        @Serializable
        class TopLevelButton(
            val buttonRenderer: ButtonRenderer?,
        ) {
            @Serializable
            class ButtonRenderer(
                val navigationEndpoint: NavigationEndpoint,
            )
        }
    }
}
