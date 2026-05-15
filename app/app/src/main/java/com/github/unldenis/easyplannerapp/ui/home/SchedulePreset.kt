package com.github.unldenis.easyplannerapp.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.unldenis.easyplannerapp.R

internal enum class SchedulePreset {
    Minutely,
    Hourly,
    Daily,
    Weekly,
    Monthly,
    Other,
    ;

    fun toCalendarExpr(): String? =
        when (this) {
            Minutely -> "minutely"
            Hourly -> "hourly"
            Daily -> "daily"
            Weekly -> "weekly"
            Monthly -> "monthly"
            Other -> null
        }
}

@Composable
internal fun SchedulePreset.displayLabel(): String =
    when (this) {
        SchedulePreset.Minutely -> stringResource(R.string.schedule_minutely)
        SchedulePreset.Hourly -> stringResource(R.string.schedule_hourly)
        SchedulePreset.Daily -> stringResource(R.string.schedule_daily)
        SchedulePreset.Weekly -> stringResource(R.string.schedule_weekly)
        SchedulePreset.Monthly -> stringResource(R.string.schedule_monthly)
        SchedulePreset.Other -> stringResource(R.string.schedule_other)
    }
