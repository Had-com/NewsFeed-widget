package com.newsfeed.widget.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesFetcherTest {

    @Test
    fun `parses two notes each with their own bullets`() {
        val markdown = """
            # Release notes

            ## Note 1
            - Add public Telegram channels as a feed source.

            ## Note 2
            - New "Custom" theme: pick your own font and background colors.
        """.trimIndent()

        val notes = ReleaseNotesFetcher.parseNotes(markdown)

        assertEquals(2, notes.size)
        assertEquals(1, notes[0].id)
        assertEquals(listOf("Add public Telegram channels as a feed source."), notes[0].bullets)
        assertEquals(2, notes[1].id)
        assertEquals(
            listOf("New \"Custom\" theme: pick your own font and background colors."),
            notes[1].bullets,
        )
    }

    @Test
    fun `a note with multiple bullet lines keeps them all in order`() {
        val markdown = """
            ## Note 1
            - First bullet.
            - Second bullet.
            - Third bullet.
        """.trimIndent()

        val notes = ReleaseNotesFetcher.parseNotes(markdown)

        assertEquals(1, notes.size)
        assertEquals(listOf("First bullet.", "Second bullet.", "Third bullet."), notes[0].bullets)
    }

    @Test
    fun `empty content produces no notes`() {
        assertEquals(emptyList<ReleaseNote>(), ReleaseNotesFetcher.parseNotes(""))
    }

    @Test
    fun `a heading with no bullet lines under it is skipped`() {
        val markdown = """
            ## Note 1

            ## Note 2
            - Only note 2 has real content.
        """.trimIndent()

        val notes = ReleaseNotesFetcher.parseNotes(markdown)

        assertEquals(1, notes.size)
        assertEquals(2, notes[0].id)
    }

    @Test
    fun `text before the first heading is ignored`() {
        val markdown = """
            # Release notes

            Some intro text that isn't a bullet under any note.

            ## Note 1
            - Real content.
        """.trimIndent()

        val notes = ReleaseNotesFetcher.parseNotes(markdown)

        assertEquals(1, notes.size)
        assertEquals(listOf("Real content."), notes[0].bullets)
    }
}
