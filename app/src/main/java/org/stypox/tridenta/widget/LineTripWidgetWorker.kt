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
            if (currentState is WidgetState.Available) {
                updateAppWidgetState(
                    context,
                    LineTripWidgetStateDefinition,
                    glanceId
                ) { WidgetState.Loading }
                LineTripWidget().update(context, glanceId)

                // TODO Retrieve the updated state
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
                ) {
                    if (trip != null) {
                        WidgetState.Available(
                            currentState.line,
                            trip,
                            referenceDateTime,
                            tripsInDayCount,
                            tripIndex,
                            prevEnabled = tripIndex > 0,
                            nextEnabled = tripIndex < tripsInDayCount - 1,
                            currentState.directionFilter,
                        )
                    } else {
                        WidgetState.Unavailable(message = "Something went wrong")
                    }
                }
            }
            LineTripWidget().update(context, glanceId)
        }
        return Result.success()
    }
}