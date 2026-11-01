package com.metrolist.innertubex.models

import java.util.Locale

internal fun defaultYouTubeLocale(): YouTubeLocale = systemYouTubeLocale(Locale.getDefault())

internal fun systemYouTubeLocale(locale: Locale): YouTubeLocale =
    YouTubeLocale(
        gl = locale.country.uppercase(Locale.ROOT),
        hl = locale.toLanguageTag(),
    )
