import utils.zip
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * @author Alexandre Chau <alexandre@pocketcampus.org>
 *         Copyright (c) 2024 PocketCampus Sàrl
 */
class ListTest {
    @Test
    fun zipListsOfSameLength() {
        val list1 = listOf(1, 2, 3)
        val list2 = listOf(4, 5, 6)
        val list3 = listOf(7, 8, 9)
        assertEquals(listOf(listOf(1, 4, 7), listOf(2, 5, 8), listOf(3, 6, 9)), zip(list1, list2, list3))
    }

    @Test
    fun zipListsOfDifferentLengths() {
        val list1 = listOf(1)
        val list2 = listOf(4, 5)
        val list3 = listOf(7, 8, 9)
        assertEquals(listOf(listOf(1, 4, 7)), zip(list1, list2, list3))
    }

    @Test
    fun zipEmptyList() {
        val list1 = emptyList<Int>()
        val list2 = listOf(4, 5)
        val list3 = listOf(7, 8, 9)
        assertEquals(emptyList(), zip(list1, list2, list3))
    }
}