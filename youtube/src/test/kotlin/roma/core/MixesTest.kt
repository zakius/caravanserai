package roma.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MixesTest {
    private fun playlist(id: String, title: String) = Item("playlist", id, Text(title, title), Text("YouTube Music", "YouTube Music"), null)

    @Test fun identifyAndOrder() {
        // Home in YouTube's order of the day, with editorial playlists, an album and a duplicate mixed in.
        val home = listOf(
            playlist("RDTMAK5uy_nilrsVWxrKskY0ZUpVZ3zpB0u4LwWTVJ4", "Replay Mix"),
            playlist("RDCLAK5uy_ljyDz8FJv4xec1TEdCPzYAHasU-OgXTeU", "All Hits"),
            playlist("RDTMAK5uy_zzzzOtherMixzzzzzzzzzzzzzzzzzzzzz", "Chill Mix"),
            playlist("RDTMAK5uy_lz2owBgwWf1mjzyn_NbxzMViQzIg8IAIg", "My Mix 2"),
            Item("album", "MPREb_abc", Text("Album", "Album"), Text("", ""), null),
            playlist("RDTMAK5uy_mZtXeU08kxXJOUhL0ETdAuZTh1z7aAFAo", "Archive Mix"),
            playlist("RDTMAK5uy_n_5IN6hzAOwdCnM8D8rzrs3vDl12UcZpA", "Discover Mix"),
            playlist("RDTMAK5uy_nGQKSMIkpr4o9VI_2i56pkGliD6FQRo50", "My Mix 1"),
            playlist("VLRDTMAK5uy_kset8DisdE7LSD4TNjEVvrKRTmG7a56sY", "マイスーパーミックス"), // localized title, VL browse ID
            playlist("RDTMAK5uy_lr0LWzGrq6FU9GIxWvFHTRPQD2LHMqlFA", "New Release Mix"),
            playlist("RDTMAK5uy_nGQKSMIkpr4o9VI_2i56pkGliD6FQRo50", "My Mix 1"),
            playlist("PLxyz", "Someone's playlist"),
        )
        assertEquals(
            listOf("マイスーパーミックス", "My Mix 1", "My Mix 2", "Discover Mix", "New Release Mix", "Replay Mix", "Archive Mix", "Chill Mix"),
            personalMixes(home).map { it.title.original },
        )
        assertEquals(emptyList(), personalMixes(home.filter { !isPersonalMix(it.id) }))
    }

    @Test fun mixSongsIgnoreTheLeash() {
        fun song(id: String, title: String, artist: String) = Song(id, Text(title, title), listOf(Named(artist, Text(artist, artist))))
        val page = listOf(song("1", "a", "Z"), song("2", "b (Live)", "Y"), song("3", "c", "X"), song("4", "d", "W"), song("1", "a", "Z"))
        val got = filterMix(page, listOf(song("4", "d", "W")), Bans(artists = setOf("X")), RadioSettings(leash = "close"))
        assertEquals(listOf("1"), got.map { it.id }) // live version, banned artist, already queued and repeats dropped; Z kept off any leash
    }
}
