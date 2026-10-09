package dev.claudefleet.mobile.notify

import java.util.Calendar

actual fun localMinuteOfDay(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
