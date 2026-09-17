package com.kert0n.medapp.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.settings.LanguageChoice

/**
 * Язык (PLAN H3 №27): три строки с переключателем — «как в системе», русский, English.
 * Названия языков — на своём языке и не переводятся: человек ищет свой язык глазами, а не
 * переводом. Нажимается вся строка, роль `RadioButton` — для экранного чтеца.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageScreen(
    current: LanguageChoice,
    onChoose: (LanguageChoice) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.language_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().selectableGroup()) {
            for (language in LanguageChoice.entries) {
                ListItem(
                    headlineContent = { Text(language.words()) },
                    leadingContent = { RadioButton(selected = language == current, onClick = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = language == current, role = Role.RadioButton, onClick = { onChoose(language) })
                )
            }
        }
    }
}

/** Название языка на нём самом; переводится только «как в системе». */
@Composable
fun LanguageChoice.words(): String = when (this) {
    LanguageChoice.SYSTEM -> stringResource(R.string.language_system)
    LanguageChoice.RUSSIAN -> stringResource(R.string.language_russian)
    LanguageChoice.ENGLISH -> stringResource(R.string.language_english)
}
