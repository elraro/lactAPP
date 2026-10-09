package eu.elraro.babytracker.ui.stats

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.elraro.babytracker.R
import eu.elraro.babytracker.stats.Change
import eu.elraro.babytracker.stats.Correlation
import eu.elraro.babytracker.stats.Diapers
import eu.elraro.babytracker.stats.Distribution
import eu.elraro.babytracker.stats.Feeding
import eu.elraro.babytracker.stats.Patterns
import eu.elraro.babytracker.stats.Sleep
import eu.elraro.babytracker.stats.WakeWindows
import eu.elraro.babytracker.ui.common.EmptyState
import eu.elraro.babytracker.ui.common.ErrorText
import eu.elraro.babytracker.ui.common.Formatters
import eu.elraro.babytracker.ui.common.PrimaryButton
import eu.elraro.babytracker.ui.common.durationText
import eu.elraro.babytracker.ui.common.rememberFormatters
import java.time.Duration
import java.time.LocalDate

private const val SECONDS_PER_HOUR = 3600f
private const val SECONDS_PER_MINUTE = 60
private const val HALF_HOUR_MINUTES = 30
private const val MINUTES_PER_DAY = 24 * 60

@Composable
fun StatsScreen(
    modifier: Modifier = Modifier,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ai by viewModel.ai.collectAsStateWithLifecycle()
    // Explanations are written in the language the app is shown in.
    val language = if (LocalConfiguration.current.locales[0].language == "es") "es" else "en"
    LaunchedEffect(Unit) { viewModel.refreshAiStatus() }
    StatsContent(
        state = state,
        onRange = viewModel::setRange,
        modifier = modifier,
        aiCard = { AiCard(ai, onKind = viewModel::setAiKind, onExplain = { viewModel.explain(language) }) },
    )
}

@Composable
fun StatsContent(
    state: StatsUiState,
    onRange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    aiCard: @Composable () -> Unit = {},
) {
    val formatters = rememberFormatters()
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatsViewModel.RANGES.forEach { days ->
                FilterChip(
                    selected = state.days == days,
                    onClick = { onRange(days) },
                    label = { Text(pluralStringResource(R.plurals.stats_range_days, days, days)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        val stats = state.stats
        when {
            state.loading -> Unit
            stats == null -> EmptyState(Icons.Outlined.Insights, R.string.stats_empty_title, R.string.stats_empty_body)
            else -> {
                FeedingCard(stats.feeding, formatters)
                SleepCard(stats.sleep, formatters)
                WakeWindowsCard(stats.wakeWindows)
                PatternsCard(stats.patterns, formatters)
                DiapersCard(stats.diapers, formatters)
                aiCard()
            }
        }
    }
}

// ---- AI explanation ----

/**
 * The optional explanation. Hidden entirely when the server has no AI provider (or has not
 * answered yet); a pointer to Settings when the family has not switched it on.
 */
@Composable
private fun AiCard(
    ai: AiUiState,
    onKind: (String) -> Unit,
    onExplain: () -> Unit,
) {
    val status = ai.status?.takeIf { it.configured } ?: return
    StatsCard(R.string.ai_card_title) {
        if (!status.enabled) {
            Text(
                text = stringResource(R.string.ai_card_disabled),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@StatsCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatsViewModel.AI_KINDS.forEach { kind ->
                FilterChip(
                    selected = ai.kind == kind,
                    onClick = { onKind(kind) },
                    enabled = !ai.working,
                    label = { Text(stringResource(aiKindLabel(kind))) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        if (ai.working) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.ai_working), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            PrimaryButton(text = R.string.action_explain, onClick = onExplain)
        }
        ai.error?.let { ErrorText(it) }
        ai.analysis?.let { analysis ->
            // Three labelled parts keep recorded facts, the AI's reading and suggestions apart.
            AiSection(R.string.ai_section_observations, analysis.result.observations.map { it.text })
            AiSection(R.string.ai_section_interpretation, analysis.result.interpretation.map { it.text })
            AiSection(R.string.ai_section_suggestions, analysis.result.suggestions.map { it.text })
            Text(
                text = stringResource(R.string.ai_generated_by, analysis.provider),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Shown by the app whether or not the model mentions it.
        Text(
            text = stringResource(R.string.ai_disclaimer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AiSection(
    @StringRes title: Int,
    texts: List<String>,
) {
    if (texts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        texts.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
    }
}

@StringRes
private fun aiKindLabel(kind: String): Int =
    when (kind) {
        "sleep" -> R.string.ai_kind_sleep
        "feeding" -> R.string.ai_kind_feeding
        else -> R.string.ai_kind_overview
    }

// ---- building blocks ----

@Composable
private fun StatsCard(
    @StringRes title: Int,
    content: @Composable () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

/** A labelled figure. A null [value] is shown as "not enough data", never as zero. */
@Composable
private fun Figure(
    label: String,
    value: String?,
    detail: String? = null,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value ?: stringResource(R.string.stats_not_enough),
            style = if (value == null) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
            color =
                if (value ==
                    null
                ) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun seconds(value: Int): String = durationText(Duration.ofSeconds(value.toLong()))

@Composable
private fun ml(value: Int): String = stringResource(R.string.amount_ml, value)

@Composable
private fun sample(count: Int): String = pluralStringResource(R.plurals.stats_sample, count, count)

/** "1 h 42 min (usually 1 h 31 min – 1 h 55 min)", or null below the minimum sample. */
@Composable
private fun typicalDuration(d: Distribution): String? {
    val median = d.median ?: return null
    val p25 = d.p25 ?: return null
    val p75 = d.p75 ?: return null
    return stringResource(R.string.stats_median_range, seconds(median), seconds(p25), seconds(p75))
}

private fun dayLabel(
    date: String,
    formatters: Formatters,
): String = formatters.shortDate(LocalDate.parse(date))

// ---- cards ----

@Composable
private fun FeedingCard(
    feeding: Feeding,
    formatters: Formatters,
) {
    StatsCard(R.string.stats_feeding) {
        Figure(
            label = stringResource(R.string.stats_today),
            value = ml(feeding.todayMl),
            detail = pluralStringResource(R.plurals.stats_feeds, feeding.todayFeeds, feeding.todayFeeds),
        )
        Figure(
            label = stringResource(R.string.stats_total_in_range),
            value = ml(feeding.totalMl),
            detail = pluralStringResource(R.plurals.stats_feeds, feeding.feeds, feeding.feeds),
        )
        Figure(stringResource(R.string.stats_avg_per_day), feeding.avgDailyMl.value?.let { ml(it) })
        Figure(stringResource(R.string.stats_feeds_per_day), feeding.feedsPerDayX10.value?.let(formatters::tenths))
        val mean = feeding.amountMl.mean
        val median = feeding.amountMl.median
        Figure(
            label = stringResource(R.string.stats_amount_per_feed),
            value =
                if (mean != null &&
                    median != null
                ) {
                    stringResource(R.string.stats_mean_and_median, ml(mean), ml(median))
                } else {
                    null
                },
        )
        Figure(stringResource(R.string.stats_time_between_feeds), typicalDuration(feeding.intervalS))
        BarChart(
            title = stringResource(R.string.stats_chart_daily_ml),
            bars = feeding.daily.map { Bar(it.totalMl.toFloat(), dayLabel(it.date, formatters)) },
            formatValue = { it.toInt().toString() },
        )
        BarChart(
            title = stringResource(R.string.stats_chart_by_hour),
            bars = feeding.byHour.map { Bar(it.totalMl.toFloat(), formatters.timeOfDay(it.hour * SECONDS_PER_MINUTE)) },
            formatValue = { it.toInt().toString() },
        )
    }
}

@Composable
private fun DiapersCard(
    diapers: Diapers,
    formatters: Formatters,
) {
    StatsCard(R.string.stats_diapers) {
        Figure(
            label = stringResource(R.string.stats_today),
            value = diapers.today.total.toString(),
            detail =
                stringResource(
                    R.string.stats_diaper_breakdown,
                    diapers.today.wet,
                    diapers.today.dirty,
                    diapers.today.both,
                ),
        )
        Figure(
            label = stringResource(R.string.stats_total_in_range),
            value = diapers.total.total.toString(),
            detail =
                stringResource(
                    R.string.stats_diaper_breakdown,
                    diapers.total.wet,
                    diapers.total.dirty,
                    diapers.total.both,
                ),
        )
        Figure(stringResource(R.string.stats_diapers_per_day), diapers.perDayX10.value?.let(formatters::tenths))
        BarChart(
            title = stringResource(R.string.stats_chart_daily_diapers),
            bars = diapers.daily.map { Bar(it.total.toFloat(), dayLabel(it.date, formatters)) },
            formatValue = { it.toInt().toString() },
        )
    }
}

@Composable
private fun SleepCard(
    sleep: Sleep,
    formatters: Formatters,
) {
    StatsCard(R.string.card_sleep) {
        Figure(
            label = stringResource(R.string.stats_today),
            value = seconds(sleep.today.totalS),
            detail = pluralStringResource(R.plurals.stats_naps, sleep.today.naps, sleep.today.naps),
        )
        Figure(
            stringResource(R.string.stats_sleep_total),
            sleep.avgTotalS.value?.let {
                seconds(it)
            },
            stringResource(R.string.stats_avg_per_day),
        )
        Figure(
            stringResource(R.string.stats_sleep_night),
            sleep.avgNightS.value?.let {
                seconds(it)
            },
            stringResource(R.string.stats_avg_per_day),
        )
        Figure(
            stringResource(R.string.stats_sleep_day),
            sleep.avgDayS.value?.let {
                seconds(it)
            },
            stringResource(R.string.stats_avg_per_day),
        )
        Figure(stringResource(R.string.stats_naps_per_day), sleep.napsPerDayX10.value?.let(formatters::tenths))
        Figure(
            stringResource(R.string.stats_nap_duration),
            typicalDuration(sleep.napDurationS),
            sample(sleep.napDurationS.sampleSize),
        )
        Figure(stringResource(R.string.stats_longest_nap), sleep.napDurationS.max?.let { seconds(it) })
        sleep.napStarts.forEach { start ->
            Figure(
                label = stringResource(R.string.stats_nap_start, start.ordinal),
                value = start.meanMinuteOfDay?.let(formatters::timeOfDay),
                detail = sample(start.sampleSize),
            )
        }
        BarChart(
            title = stringResource(R.string.stats_chart_daily_sleep),
            bars = sleep.daily.map { Bar(it.totalS / SECONDS_PER_HOUR, dayLabel(it.date, formatters)) },
            formatValue = { formatters.tenths((it * TENTHS).toInt()) },
        )
    }
}

@Composable
private fun WakeWindowsCard(windows: WakeWindows) {
    StatsCard(R.string.stats_wake_windows) {
        Figure(
            stringResource(R.string.stats_wake_overall),
            typicalDuration(windows.overall),
            sample(windows.overall.sampleSize),
        )
        windows.byOrdinal.forEach { ordinal ->
            val distribution = Distribution(ordinal.median, ordinal.p25, ordinal.p75, sampleSize = ordinal.sampleSize)
            Figure(
                stringResource(R.string.stats_wake_ordinal, ordinal.ordinal),
                typicalDuration(distribution),
                sample(ordinal.sampleSize),
            )
        }
        BarChart(
            title = stringResource(R.string.stats_chart_wake_histogram),
            bars = windows.histogram.map { Bar(it.count.toFloat(), (it.fromS / SECONDS_PER_MINUTE).toString()) },
            formatValue = { it.toInt().toString() },
        )
    }
}

@Composable
private fun PatternsCard(
    patterns: Patterns,
    formatters: Formatters,
) {
    val correlation = patterns.wakeWindowVsNap
    val hasContent =
        correlation.strength != null ||
            patterns.napByStartTime.isNotEmpty() ||
            patterns.changes.any { it.recent != null }
    StatsCard(R.string.stats_patterns) {
        if (!hasContent) {
            Text(
                text = stringResource(R.string.stats_not_enough),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@StatsCard
        }
        CorrelationText(correlation)
        patterns.napByStartTime.forEach { bucket ->
            Figure(
                label =
                    stringResource(
                        R.string.stats_nap_by_start,
                        formatters.timeOfDay(bucket.fromMinute),
                        formatters.timeOfDay((bucket.fromMinute + HALF_HOUR_MINUTES) % MINUTES_PER_DAY),
                    ),
                value =
                    stringResource(
                        R.string.stats_nap_by_start_value,
                        seconds(bucket.medianS),
                        sample(bucket.sampleSize),
                    ),
            )
        }
        if (patterns.changes.any { it.recent != null }) {
            Text(stringResource(R.string.stats_changes), style = MaterialTheme.typography.titleMedium)
            patterns.changes.forEach { ChangeRow(it) }
        }
    }
}

/** Describes the association in words chosen never to suggest cause (docs/STATISTICS.md §8). */
@Composable
private fun CorrelationText(correlation: Correlation) {
    val rho = correlation.spearmanX1000 ?: return
    val strength =
        when (correlation.strength) {
            "weak" -> R.string.stats_strength_weak
            "moderate" -> R.string.stats_strength_moderate
            "strong" -> R.string.stats_strength_strong
            else -> null
        }
    val sentence =
        when {
            strength == null -> R.string.stats_pattern_none
            rho > 0 -> R.string.stats_pattern_longer
            else -> R.string.stats_pattern_shorter
        }
    Text(stringResource(sentence), style = MaterialTheme.typography.bodyLarge)
    val naps = pluralStringResource(R.plurals.stats_naps, correlation.sampleSize, correlation.sampleSize)
    Text(
        text =
            if (strength ==
                null
            ) {
                naps
            } else {
                stringResource(R.string.stats_strength_and_sample, stringResource(strength), naps)
            },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = stringResource(R.string.stats_pattern_caution),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ChangeRow(change: Change) {
    val recent = change.recent ?: return
    val previous = change.previous ?: return
    val (label, isVolume) =
        when (change.metric) {
            "wake_window_median_s" -> R.string.stats_change_wake to false
            "nap_duration_median_s" -> R.string.stats_change_nap to false
            "daily_intake_avg_ml" -> R.string.stats_change_intake to true
            "daily_sleep_avg_s" -> R.string.stats_change_sleep to false
            else -> return
        }
    val now = if (isVolume) ml(recent) else seconds(recent)
    val before = if (isVolume) ml(previous) else seconds(previous)
    Figure(stringResource(label), stringResource(R.string.stats_change_value, now, before))
}

private const val TENTHS = 10
