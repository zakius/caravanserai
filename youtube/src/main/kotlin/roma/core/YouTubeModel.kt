package roma.core

import com.metrolist.innertube.models.SongItem

fun Romanizer.song(item: SongItem): Song {
    val artistNames = item.artists.map { it.name }
    val albumName = item.album?.name
    val artistId = item.artists.firstOrNull { it.id != null }?.id
    val title = text(item.title, artistNames + listOfNotNull(albumName), item.id, artistId, titleOf = true)
    val artists = item.artists.map { a -> Named(a.id, text(a.name, listOf(item.title) + listOfNotNull(albumName), item.id, a.id)) }
    val album = item.album?.let { Named(it.id, text(it.name, listOf(item.title) + artistNames, item.id, artistId, titleOf = true)) }
    return Song(item.id, title, artists, album, item.duration ?: 0, item.thumbnail, item.isVideoSong)
}

fun Romanizer.item(y: com.metrolist.innertube.models.YTItem): Item? = when (y) {
    is SongItem -> song(y).let { Item("song", it.id, it.title, it.artistText, it.thumb, it) }
    is com.metrolist.innertube.models.AlbumItem -> {
        val artists = y.artists.orEmpty().joinToString(", ") { it.name }
        Item("album", y.browseId, text(y.title, listOf(artists), titleOf = true), text(artists, listOf(y.title)), y.thumbnail)
    }
    is com.metrolist.innertube.models.PlaylistItem -> Item("playlist", y.id, text(y.title), text(y.author?.name ?: ""), y.thumbnail)
    is com.metrolist.innertube.models.ArtistItem -> Item("artist", y.id, text(y.title, artistId = y.id), Text("", ""), y.thumbnail)
    else -> null
}
