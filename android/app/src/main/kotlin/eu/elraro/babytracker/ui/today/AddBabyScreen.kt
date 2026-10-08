package eu.elraro.babytracker.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import eu.elraro.babytracker.R
import eu.elraro.babytracker.ui.common.DatePickerDialog
import eu.elraro.babytracker.ui.common.FormTextField
import eu.elraro.babytracker.ui.common.PrimaryActionHeight
import eu.elraro.babytracker.ui.common.PrimaryButton
import eu.elraro.babytracker.ui.common.rememberFormatters
import java.time.LocalDate

private const val MAX_NAME_LENGTH = 100

/** Shown once, before anything can be recorded. */
@Composable
fun AddBabyScreen(
    today: LocalDate,
    onSave: (name: String, dateOfBirth: LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatters = rememberFormatters()
    var name by rememberSaveable { mutableStateOf("") }
    // Saved as ISO text because LocalDate is not saveable by itself.
    var birth by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val birthDate = birth?.let(LocalDate::parse)

    Column(
        modifier = modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Decorative.
            Icon(
                imageVector = Icons.Outlined.ChildCare,
                contentDescription = null,
                modifier = Modifier.size(64.dp).align(Alignment.CenterHorizontally),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.add_baby_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                text = stringResource(R.string.add_baby_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FormTextField(
                value = name,
                onValueChange = { name = it.take(MAX_NAME_LENGTH) },
                label = R.string.field_baby_name,
                error = if (submitted && name.isBlank()) R.string.error_required else null,
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            )
            Text(stringResource(R.string.field_date_of_birth), style = MaterialTheme.typography.labelLarge)
            OutlinedButton(
                onClick = { pickingDate = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryActionHeight),
            ) {
                Text(birthDate?.let(formatters::date) ?: stringResource(R.string.action_choose_date))
            }
            if (submitted && birthDate == null) {
                Text(
                    text = stringResource(R.string.error_required),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            PrimaryButton(
                text = R.string.action_save,
                onClick = {
                    submitted = true
                    if (name.isNotBlank() && birthDate != null) onSave(name, birthDate)
                },
            )
        }
    }

    if (pickingDate) {
        DatePickerDialog(
            initial = birthDate ?: today,
            onDismiss = { pickingDate = false },
            onConfirm = {
                pickingDate = false
                // A birth date in the future is a slip of the finger.
                birth = minOf(it, today).toString()
            },
        )
    }
}
