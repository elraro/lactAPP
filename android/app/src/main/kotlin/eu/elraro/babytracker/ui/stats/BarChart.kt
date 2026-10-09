package eu.elraro.babytracker.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import eu.elraro.babytracker.R

/**
 * A plain bar chart: one series, no interaction. For a screen reader the whole chart is
 * one element described by its title, lowest and highest value.
 */
@Composable
fun BarChart(
    title: String,
    bars: List<Bar>,
    formatValue: (Float) -> String,
    modifier: Modifier = Modifier,
) {
    if (bars.isEmpty()) return
    val max = bars.maxOf { it.value }
    val min = bars.minOf { it.value }
    val description = stringResource(R.string.stats_chart_description, title, formatValue(min), formatValue(max))
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Title on the left; the value of the tallest bar on the right gives the scale.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                formatValue(max),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
            val slot = size.width / bars.size
            val barWidth = slot * BAR_FILL
            val radius = CornerRadius(barWidth / 4)
            bars.forEachIndexed { index, bar ->
                val left = index * slot + (slot - barWidth) / 2
                // A faint full-height track keeps empty days visible as days.
                drawRoundRect(trackColor, Offset(left, 0f), Size(barWidth, size.height), radius, alpha = TRACK_ALPHA)
                if (max > 0f && bar.value > 0f) {
                    val height = size.height * (bar.value / max)
                    drawRoundRect(barColor, Offset(left, size.height - height), Size(barWidth, height), radius)
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(bars.first(), bars[bars.size / 2], bars.last()).forEach {
                Text(
                    it.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One bar; [label] is shown under the first, middle and last bars only, to stay legible. */
data class Bar(
    val value: Float,
    val label: String,
)

private const val BAR_FILL = 0.7f
private const val TRACK_ALPHA = 0.5f
