package dev.daniel.jigsaw.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import dev.daniel.jigsaw.R
import dev.daniel.jigsaw.puzzle.UndecodableImageException
import dev.daniel.jigsaw.source.HttpStatusException
import dev.daniel.jigsaw.source.UnexpectedResponseException
import java.net.ConnectException
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** A string resource plus its format arguments, so the view model can produce text the UI shows in its locale. */
@Immutable
data class UiText(@field:StringRes val id: Int, val args: List<Any> = emptyList()) {
    // At most two arguments: copying them for the vararg call costs nothing worth avoiding.
    @Suppress("SpreadOperator")
    @Composable
    @ReadOnlyComposable
    fun resolve(): String = stringResource(id, *args.toTypedArray())
}

/** What went wrong, in the user's terms rather than the exception's English developer message. */
fun Throwable.toUiText(): UiText = when (this) {
    is HttpStatusException -> when (code) {
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> UiText(R.string.error_access_denied)
        HTTP_NOT_FOUND -> UiText(R.string.error_not_found)
        else -> UiText(R.string.error_http, listOf(code))
    }

    is UnknownHostException -> UiText(R.string.error_unknown_host)

    is SocketTimeoutException -> UiText(R.string.error_timeout)

    is ConnectException -> UiText(R.string.error_connect)

    is SSLException -> UiText(R.string.error_tls)

    is UnexpectedResponseException -> UiText(R.string.error_unexpected_response)

    is UndecodableImageException -> UiText(R.string.error_not_an_image)

    else -> UiText(R.string.error_other, listOf(message ?: javaClass.simpleName))
}
