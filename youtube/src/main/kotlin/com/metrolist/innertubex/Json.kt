package com.metrolist.innertubex

import kotlinx.serialization.json.Json
import roma.core.HttpResponse

/** How InnerTube's JSON is written and read: nulls left out, defaults kept, unknown keys skipped. */
@PublishedApi internal val innerTubeJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** The body as JSON, decoded as [T]. */
inline fun <reified T> HttpResponse.body(): T = innerTubeJson.decodeFromString(text())
