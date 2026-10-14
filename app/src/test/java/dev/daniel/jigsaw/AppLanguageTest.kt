package dev.daniel.jigsaw

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppLanguageTest {
    @Test
    fun `regional variants map to their translation, anything else follows the system`() {
        assertEquals(AppLanguage.German, AppLanguage.fromTag("de"))
        assertEquals(AppLanguage.German, AppLanguage.fromTag("de-AT"))
        assertEquals(AppLanguage.English, AppLanguage.fromTag("en-GB"))
        assertEquals(AppLanguage.System, AppLanguage.fromTag("fr-FR"))
        assertEquals(AppLanguage.System, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.System, AppLanguage.fromTag(null))
    }
}
