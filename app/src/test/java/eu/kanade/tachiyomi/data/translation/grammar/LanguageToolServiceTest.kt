package eu.kanade.tachiyomi.data.translation.grammar

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LanguageToolServiceTest {

    private val service = LanguageToolService(
        networkHelper = mockk(relaxed = true),
        preferences = mockk(relaxed = true),
    )

    @Test
    fun `applyCorrections with empty matches returns original text unchanged`() {
        val original = "She are a good girl."
        val result = service.applyCorrections(original, emptyList())
        assertEquals(original, result)
    }

    @Test
    fun `applyCorrections replaces single match correctly`() {
        val original = "She are a good girl."
        val matches = listOf(
            LanguageToolService.LanguageToolMatch(
                offset = 4,
                length = 3,
                replacements = listOf(
                    LanguageToolService.LanguageToolReplacement("is"),
                    LanguageToolService.LanguageToolReplacement("was"),
                ),
            ),
        )

        val result = service.applyCorrections(original, matches)
        assertEquals("She is a good girl.", result)
    }

    @Test
    fun `applyCorrections replaces multiple matches backward by offset descending`() {
        // "She are a good girl and he am nice."
        // 01234567890123456789012345678901234
        val original = "She are a good girl and he am nice."
        val matches = listOf(
            // match 1 earlier in text
            LanguageToolService.LanguageToolMatch(
                offset = 4,
                length = 3,
                replacements = listOf(LanguageToolService.LanguageToolReplacement("is")),
            ),
            // match 2 later in text
            LanguageToolService.LanguageToolMatch(
                offset = 27,
                length = 2,
                replacements = listOf(LanguageToolService.LanguageToolReplacement("is")),
            ),
        )

        val result = service.applyCorrections(original, matches)
        assertEquals("She is a good girl and he is nice.", result)
    }

    @Test
    fun `applyCorrections skips matches with empty replacements`() {
        val original = "Hello world."
        val matches = listOf(
            LanguageToolService.LanguageToolMatch(
                offset = 0,
                length = 5,
                replacements = emptyList(),
            ),
        )

        val result = service.applyCorrections(original, matches)
        assertEquals(original, result)
    }

    @Test
    fun `applyCorrections skips invalid out of bounds ranges safely`() {
        val original = "Short"
        val matches = listOf(
            LanguageToolService.LanguageToolMatch(
                offset = -2,
                length = 3,
                replacements = listOf(LanguageToolService.LanguageToolReplacement("X")),
            ),
            LanguageToolService.LanguageToolMatch(
                offset = 3,
                length = 10,
                replacements = listOf(LanguageToolService.LanguageToolReplacement("Y")),
            ),
        )

        val result = service.applyCorrections(original, matches)
        assertEquals(original, result)
    }

    @Test
    fun `applyCorrections resolves conflicting subject-verb matches in sentence`() {
        val original = "This are a test sentence."
        val matches = listOf(
            LanguageToolService.LanguageToolMatch(
                offset = 0,
                length = 4,
                replacements = listOf(LanguageToolService.LanguageToolReplacement("These")),
                message = "The singular demonstrative pronoun ‘this’ does not agree " +
                    "with the plural verb ‘are’. Did you mean “these”?",
                rule = LanguageToolService.LanguageToolRule(
                    id = "THIS_NNS",
                    subId = "5",
                    issueType = "grammar",
                    category = LanguageToolService.LanguageToolCategory(id = "GRAMMAR"),
                ),
            ),
            LanguageToolService.LanguageToolMatch(
                offset = 5,
                length = 3,
                replacements = listOf(LanguageToolService.LanguageToolReplacement("is")),
                message = "The verb ‘are’ is plural. Did you mean: “is”? Did you use a verb instead of a noun?",
                rule = LanguageToolService.LanguageToolRule(
                    id = "PLURAL_VERB_AFTER_THIS",
                    subId = "2",
                    issueType = "grammar",
                    category = LanguageToolService.LanguageToolCategory(id = "GRAMMAR"),
                ),
            ),
        )

        val result = service.applyCorrections(original, matches)
        assertEquals("This is a test sentence.", result)
    }

    @Test
    fun `applyCorrections preserves casing for uppercase words`() {
        val original = "THIS ARE A TEST."
        val matches = listOf(
            LanguageToolService.LanguageToolMatch(
                offset = 5,
                length = 3,
                replacements = listOf(LanguageToolService.LanguageToolReplacement("is")),
                rule = LanguageToolService.LanguageToolRule(
                    id = "PLURAL_VERB_AFTER_THIS",
                    issueType = "grammar",
                    category = LanguageToolService.LanguageToolCategory(id = "GRAMMAR"),
                ),
            ),
        )

        val result = service.applyCorrections(original, matches)
        assertEquals("THIS IS A TEST.", result)
    }

    @Test
    fun `applyCorrections handles adjacent non-conflicting typo matches`() {
        val original = "teh caat is nice."
        val matches = listOf(
            LanguageToolService.LanguageToolMatch(
                offset = 0,
                length = 3,
                replacements = listOf(LanguageToolService.LanguageToolReplacement("the")),
                rule = LanguageToolService.LanguageToolRule(
                    id = "MORFOLOGIK_RULE_EN_US",
                    issueType = "misspelling",
                    category = LanguageToolService.LanguageToolCategory(id = "TYPOS"),
                ),
            ),
            LanguageToolService.LanguageToolMatch(
                offset = 4,
                length = 4,
                replacements = listOf(LanguageToolService.LanguageToolReplacement("cat")),
                rule = LanguageToolService.LanguageToolRule(
                    id = "MORFOLOGIK_RULE_EN_US",
                    issueType = "misspelling",
                    category = LanguageToolService.LanguageToolCategory(id = "TYPOS"),
                ),
            ),
        )

        val result = service.applyCorrections(original, matches)
        assertEquals("the cat is nice.", result)
    }
}
