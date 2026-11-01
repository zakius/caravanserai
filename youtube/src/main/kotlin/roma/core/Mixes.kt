package roma.core

/*
 * YouTube Music's personal mixes have fixed playlist IDs, the same for every account; the signed-in account's
 * cookies decide what is in them. Without cookies the same IDs still resolve, to an anonymous mix. Every one starts
 * with RDTMAK5uy_ (editorial playlists start with RDCLAK5uy_, song radio with RDAMVM). Sources: Music Assistant's
 * YT_PERSONAL_PLAYLISTS and cliamp #619; see docs/design.md.
 */
private val KNOWN_MIXES = listOf(
    "RDTMAK5uy_kset8DisdE7LSD4TNjEVvrKRTmG7a56sY", // My Supermix
    "RDTMAK5uy_nGQKSMIkpr4o9VI_2i56pkGliD6FQRo50", // My Mix 1
    "RDTMAK5uy_lz2owBgwWf1mjzyn_NbxzMViQzIg8IAIg", // My Mix 2
    "RDTMAK5uy_k5UUl0lmrrfrjMpsT0CoMpdcBz1ruAO1k", // My Mix 3
    "RDTMAK5uy_nTsa0Irmcu2li2-qHBoZxtrpG9HuC3k_Q", // My Mix 4
    "RDTMAK5uy_lfZhS7zmIcmUhsKtkWylKzc0EN0LW90-s", // My Mix 5
    "RDTMAK5uy_k78ni6Y4fyyl0r2eiKkBEICh9Q5wJdfXk", // My Mix 6
    "RDTMAK5uy_lfhhWWw9v71CPrR7MRMHgZzbH6Vku9iJc", // My Mix 7
    "RDTMAK5uy_n_5IN6hzAOwdCnM8D8rzrs3vDl12UcZpA", // Discover Mix
    "RDTMAK5uy_lr0LWzGrq6FU9GIxWvFHTRPQD2LHMqlFA", // New Release Mix
    "RDTMAK5uy_nilrsVWxrKskY0ZUpVZ3zpB0u4LwWTVJ4", // Replay Mix
)

fun isPersonalMix(playlistId: String) = playlistId.removePrefix("VL").startsWith("RDTMAK5uy_")

/** The personal mixes among [items], in a fixed order: the known ones as listed above, then any others by title. */
fun personalMixes(items: List<Item>): List<Item> =
    items.filter { it.kind == "playlist" && isPersonalMix(it.id) }
        .distinctBy { it.id.removePrefix("VL") }
        .sortedWith(mixOrder { it.id })

/** Orders mixes by the YouTube playlist id [youtubeId] gives (null when unknown): the known ones first, then by title. */
fun mixOrder(youtubeId: (Item) -> String?): Comparator<Item> =
    compareBy({ KNOWN_MIXES.indexOf(youtubeId(it)?.removePrefix("VL")).let { i -> if (i < 0) KNOWN_MIXES.size else i } }, { it.title.latin.lowercase() })
