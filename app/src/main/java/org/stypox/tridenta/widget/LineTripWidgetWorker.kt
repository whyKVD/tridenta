package org.stypox.tridenta.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import org.stypox.tridenta.extractor.ROME_ZONE_ID
import org.stypox.tridenta.log.logInfo
import org.stypox.tridenta.widget.actions.WidgetEntryPoint
import org.stypox.tridenta.widget.actions.WidgetStopTripsEntryPoint
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class LineTripWidgetWorker(
    private val context: Context, params: WorkerParameters,
    //private val tripsRepository: LineTripsRepository
) :
    CoroutineWorker(context, params) {
    companion object {
        private val uniqueWorkName = LineTripWidgetWorker::class.java.simpleName

        fun enqueue(context: Context, force: Boolean = false) {
            val workManager = WorkManager.getInstance(context)
            val request =
                PeriodicWorkRequestBuilder<LineTripWidgetWorker>(15, TimeUnit.MINUTES).build()

            workManager.enqueueUniquePeriodicWork(
                uniqueWorkName = uniqueWorkName, existingPeriodicWorkPolicy = if (force) {
                    ExistingPeriodicWorkPolicy.UPDATE
                } else {
                    ExistingPeriodicWorkPolicy.KEEP
                }, request = request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName)
        }
    }

    override suspend fun doWork(): Result {
        val appWidgetManager = GlanceAppWidgetManager(context)
        appWidgetManager.getGlanceIds(LineTripWidget::class.java).forEach { glanceId ->
            val currentState: WidgetState = getAppWidgetState(
                context,
                LineTripWidgetStateDefinition, glanceId
            )
            when (currentState) {
                is WidgetState.LineTripsAvailable -> {
                    updateAppWidgetState(
                        context,
                        LineTripWidgetStateDefinition,
                        glanceId
                    ) { oldState ->
                        if (oldState is WidgetState.LineTripsAvailable) oldState.copy(
                            loading = true
                        ) else oldState
                    }
                    LineTripWidget().update(context, glanceId)

                    if (currentState.line == null) {
                        updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) {
                            WidgetState.Unavailable("Something went wrong")
                        }
                        LineTripWidget().update(context, glanceId)
                        return@forEach
                    }

                    val hiltEntryPoint =
                        EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
                    val tripsRepository = hiltEntryPoint.lineTripsRepository()
                    val referenceDateTime = ZonedDateTime.now().withZoneSameInstant(ROME_ZONE_ID)
                    val (tripsInDayCount, tripIndex, trip) = tripsRepository.getUiTrip(
                        currentState.line.lineId,
                        currentState.line.type,
                        referenceDateTime,
                        currentState.directionFilter
                    )
                    logInfo("trip: $trip")

                    updateAppWidgetState(
                        context,
                        LineTripWidgetStateDefinition,
                        glanceId
                    ) { oldState ->
                        oldState as WidgetState.LineTripsAvailable
                        if (trip != null) {
                            oldState.copy(
                                trip = trip,
                                referenceDateTime = referenceDateTime,
                                tripsInDayCount = tripsInDayCount,
                                tripIndex = tripIndex,
                                prevEnabled = tripIndex > 0,
                                nextEnabled = tripIndex < tripsInDayCount - 1,
                                loading = false
                            )
                        } else {
                            oldState.copy(error = true, loading = false)
                        }
                    }
                }

                is WidgetState.StopTripsAvailable -> {
                    updateAppWidgetState(
                        context,
                        LineTripWidgetStateDefinition,
                        glanceId
                    ) { oldState ->
                        if (oldState is WidgetState.StopTripsAvailable) oldState.copy(
                            loading = true
                        ) else oldState
                    }
                    LineTripWidget().update(context, glanceId)

                    if (currentState.stop == null) {
                        updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) {
                            WidgetState.Unavailable("Something went wrong")
                        }
                        LineTripWidget().update(context, glanceId)
                        return@forEach
                    }

                    val hiltEntryPoint =
                        EntryPointAccessors.fromApplication(
                            context,
                            WidgetStopTripsEntryPoint::class.java
                        )
                    val referenceDateTime = ZonedDateTime.now().withZoneSameInstant(ROME_ZONE_ID)
                    hiltEntryPoint.setReferenceDateTimeAsync(
                        referenceDateTime,
                        currentState.stop.stopId,
                        currentState.stop.type,
                        context,
                        glanceId
                    )
                    updateAppWidgetState(
                        context,
                        LineTripWidgetStateDefinition,
                        glanceId
                    ) { oldState ->
                        if (oldState is WidgetState.StopTripsAvailable) oldState.copy(
                            loading = false
                        ) else oldState
                    }
                }

                else -> {}
            }
            LineTripWidget().update(context, glanceId)
        }
        return Result.success()
    }
}