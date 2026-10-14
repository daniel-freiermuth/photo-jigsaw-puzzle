package dev.daniel.jigsaw.ui

import dev.daniel.jigsaw.R
import dev.daniel.jigsaw.source.HttpStatusException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException

class UiTextTest {
    @Test
    fun `password problems and missing shares get their own explanation, other statuses show the code`() {
        assertEquals(UiText(R.string.error_access_denied), HttpStatusException(401, "u").toUiText())
        assertEquals(UiText(R.string.error_access_denied), HttpStatusException(403, "u").toUiText())
        assertEquals(UiText(R.string.error_not_found), HttpStatusException(404, "u").toUiText())
        assertEquals(UiText(R.string.error_http, listOf(502)), HttpStatusException(502, "u").toUiText())
    }

    @Test
    fun `unrecognised failures still tell the user what happened`() {
        assertEquals(UiText(R.string.error_other, listOf("disk full")), IOException("disk full").toUiText())
        assertEquals(UiText(R.string.error_other, listOf("IOException")), IOException().toUiText())
    }
}
