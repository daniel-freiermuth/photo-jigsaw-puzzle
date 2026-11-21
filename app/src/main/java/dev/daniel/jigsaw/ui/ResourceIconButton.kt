package dev.daniel.jigsaw.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource

/** [IconButton] showing the drawable [icon], described for accessibility by the string [description]. */
@Composable
internal fun ResourceIconButton(
    @DrawableRes icon: Int,
    @StringRes description: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(painterResource(icon), contentDescription = stringResource(description))
    }
}
