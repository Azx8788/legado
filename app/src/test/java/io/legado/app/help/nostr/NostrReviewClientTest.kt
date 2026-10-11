package io.legado.app.help.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrReviewClientTest {

    @Test
    fun bookIdentifierIsStableAcrossWhitespaceAndCase() {
        val first = NostrReviewClient.bookIdentifier("  The   Book ", "Author")
        val second = NostrReviewClient.bookIdentifier("the book", " author ")

        assertEquals(first, second)
        assertTrue(first.startsWith("legado:book:"))
    }

    @Test
    fun bookIdentifierSeparatesDifferentAuthors() {
        val first = NostrReviewClient.bookIdentifier("The Book", "Author A")
        val second = NostrReviewClient.bookIdentifier("The Book", "Author B")

        assertNotEquals(first, second)
    }
}