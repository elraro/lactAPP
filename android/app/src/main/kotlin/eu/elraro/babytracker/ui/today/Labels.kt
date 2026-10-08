package eu.elraro.babytracker.ui.today

import androidx.annotation.StringRes
import eu.elraro.babytracker.R
import eu.elraro.babytracker.domain.DiaperKind
import eu.elraro.babytracker.domain.MilkKind
import eu.elraro.babytracker.domain.SleepKind

@get:StringRes
val DiaperKind.label: Int
    get() =
        when (this) {
            DiaperKind.WET -> R.string.diaper_wet
            DiaperKind.DIRTY -> R.string.diaper_dirty
            DiaperKind.BOTH -> R.string.diaper_both
        }

@get:StringRes
val SleepKind.label: Int
    get() =
        when (this) {
            SleepKind.NAP -> R.string.sleep_nap
            SleepKind.NIGHT -> R.string.sleep_night
        }

@get:StringRes
val MilkKind.label: Int
    get() =
        when (this) {
            MilkKind.FORMULA -> R.string.milk_formula
            MilkKind.BREAST_MILK -> R.string.milk_breast
            MilkKind.MIXED -> R.string.milk_mixed
        }
