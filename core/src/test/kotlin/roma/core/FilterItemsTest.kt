package roma.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FilterItemsTest {
    private val items = listOf(
        Item("artist", "1", Text("YOASOBI", "YOASOBI"), Text("", ""), null),
        Item("artist", "2", Text("米津玄師", "Kenshi Yonezu"), Text("", ""), null),
        Item("album", "3", Text("夜に駆ける", "Yoru ni Kakeru"), Text("YOASOBI", "YOASOBI"), null),
        Item(
            "song",
            "4",
            Text("群青", "Gunjō"),
            Text("YOASOBI", "YOASOBI"),
            null,
            Song("4", Text("群青", "Gunjō"), listOf(Named("1", Text("YOASOBI", "YOASOBI"))), Named("a", Text("THE BOOK", "THE BOOK"))),
        ),
    )

    private fun run(query: String): List<String> {
        val folded = Array(items.size) { foldItem(items[it]) }
        return filterItems(items, folded, query).map { it.id }
    }

    @Test fun blankKeepsAllInOrder() {
        assertEquals(listOf("1", "2", "3", "4"), run("   "))
    }

    @Test fun matchesLatinOriginalMacronAndSubtitle() {
        assertEquals(listOf("2"), run("yonezu"))
        assertEquals(listOf("2"), run("米津"))
        assertEquals(listOf("4"), run("gunjo"))
        assertEquals(listOf("4"), run("the book"))
        assertEquals(listOf("1", "3", "4"), run("yoasobi"))
    }

    @Test fun toleratesSmallTypo() {
        assertEquals(listOf("3"), run("yorunikakero"))
        assertEquals(emptyList(), run("zzzzz"))
    }
}
