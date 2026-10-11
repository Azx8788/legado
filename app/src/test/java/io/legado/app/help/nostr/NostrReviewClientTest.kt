package io.legado.app.help.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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

    @Test
    fun paragraphMatchUsesContextWhenChapterReferenceDiffers() {
        val paragraphs = listOf(
            "A distinct preceding sentence establishes the first location.",
            "Repeated paragraph text appears in more than one chapter.",
            "An unmistakable following sentence closes the first context."
        )
        val excerpt = NostrReviewClient.NostrEvent(
            id = "event-id",
            pubkey = "pubkey",
            content = paragraphs[1],
            createdAt = 1,
            kind = 9802,
            tags = listOf(
                listOf("r", "https://other-source.example/chapter"),
                listOf("context", "sentence establishes the first location. An unmistakable following")
            )
        )

        val matches = NostrReviewClient.matchParagraphs(
            listOf(excerpt),
            paragraphs,
            "https://current-source.example/chapter"
        )

        assertTrue(matches.containsKey(2))
    }

    @Test
    fun paragraphMatchRejectsConflictingContextWithoutChapterReference() {
        val paragraphs = listOf(
            "A current chapter has neighbors that do not match this event.",
            "Repeated paragraph text appears in more than one chapter.",
            "The next paragraph in the current book is also different."
        )
        val excerpt = NostrReviewClient.NostrEvent(
            id = "event-id",
            pubkey = "pubkey",
            content = paragraphs[1],
            createdAt = 1,
            kind = 9802,
            tags = listOf(
                listOf("r", "https://other-source.example/chapter"),
                listOf("context", "unrelated words from another chapter's context")
            )
        )

        val matches = NostrReviewClient.matchParagraphs(
            listOf(excerpt),
            paragraphs,
            "https://current-source.example/chapter"
        )

        assertFalse(matches.containsKey(2))
    }

    @Test
    fun eventVerificationRejectsMismatchedEventIdBeforeSignatureCheck() {
        val validLengthHex = "00".repeat(32)
        val signatureHex = "00".repeat(64)

        val isValid = NostrReviewClient.verifyEvent(
            id = validLengthHex,
            pubkey = validLengthHex,
            createdAt = 1,
            kind = 9802,
            tags = emptyList(),
            content = "excerpt",
            signature = signatureHex
        )

        assertFalse(isValid)
    }
}