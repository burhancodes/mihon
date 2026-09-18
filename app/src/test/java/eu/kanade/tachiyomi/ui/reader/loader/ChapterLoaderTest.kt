package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.Source
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class ChapterLoaderTest {

    private val chapterLoader = ChapterLoader(
        context = mockk(relaxed = true),
        downloadManager = mockk(relaxed = true),
        downloadProvider = mockk(relaxed = true),
        chapterCache = mockk(relaxed = true),
        translationManager = mockk(relaxed = true),
        readerPreferences = mockk(relaxed = true),
        manga = mockk(relaxed = true),
        source = mockk(relaxed = true),
    )

    private fun createManga(
        title: String,
        genres: List<String>? = null,
        description: String? = null,
        url: String = "",
    ): Manga {
        val manga = mockk<Manga>(relaxed = true)
        every { manga.title } returns title
        every { manga.genre } returns genres
        every { manga.description } returns description
        every { manga.url } returns url
        return manga
    }

    private fun createSource(lang: String): Source {
        val source = mockk<Source>(relaxed = true)
        every { source.lang } returns lang
        return source
    }

    @Test
    fun `title literally containing Raw as a standalone word matches`() {
        val manga = createManga("Solo Leveling [Raw]")
        val source = createSource("en")
        assertTrue(chapterLoader.isRawManga(manga, source, "ENG"))
    }

    @Test
    fun `title containing raw as a substring of another word does not match`() {
        val strawberryManga = createManga("Strawberry Panic")
        val drawingManga = createManga("Drawing Love")
        val crawlManga = createManga("Dungeon Crawl")
        val brawlManga = createManga("Super Brawl Universe")
        val source = createSource("en")

        assertFalse(chapterLoader.isRawManga(strawberryManga, source, "ENG"))
        assertFalse(chapterLoader.isRawManga(drawingManga, source, "ENG"))
        assertFalse(chapterLoader.isRawManga(crawlManga, source, "ENG"))
        assertFalse(chapterLoader.isRawManga(brawlManga, source, "ENG"))
    }

    @Test
    fun `case variations of Raw match correctly`() {
        val source = createSource("en")
        val rawUpper = createManga("Kingdom RAW")
        val rawTitleCase = createManga("Kingdom Raw")
        val rawLower = createManga("Kingdom raw")

        assertTrue(chapterLoader.isRawManga(rawUpper, source, "ENG"))
        assertTrue(chapterLoader.isRawManga(rawTitleCase, source, "ENG"))
        assertTrue(chapterLoader.isRawManga(rawLower, source, "ENG"))
    }

    @Test
    fun `genre and description with standalone raw word match`() {
        val source = createSource("en")
        val genreManga = createManga("Action Hero", genres = listOf("Fantasy", "Raw"))
        val descManga = createManga("Mystery Story", description = "This is the Korean raw version.")

        assertTrue(chapterLoader.isRawManga(genreManga, source, "ENG"))
        assertTrue(chapterLoader.isRawManga(descManga, source, "ENG"))
    }

    @Test
    fun `genre and description with raw as substring do not match`() {
        val source = createSource("en")
        val genreManga = createManga("Action Hero", genres = listOf("Fantasy", "Drawing"))
        val descManga = createManga("Mystery Story", description = "A brawl in strawberry fields.")

        assertFalse(chapterLoader.isRawManga(genreManga, source, "ENG"))
        assertFalse(chapterLoader.isRawManga(descManga, source, "ENG"))
    }

    @Test
    fun `manga url containing raw with word boundaries matches`() {
        val source = createSource("en")
        val rawUrlManga = createManga("Circles", url = "/manga/circles-raw/")
        val secretClassRaw = createManga("Secret Class", url = "/manga/secret-class-raw")
        val normalManga = createManga("Strawberry Panic", url = "/manga/strawberry-panic/")

        assertTrue(chapterLoader.isRawManga(rawUrlManga, source, "ENG"))
        assertTrue(chapterLoader.isRawManga(secretClassRaw, source, "ENG"))
        assertFalse(chapterLoader.isRawManga(normalManga, source, "ENG"))
    }
}
