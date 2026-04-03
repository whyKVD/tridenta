package org.stypox.tridenta.widget

import kotlinx.serialization.Serializable
import org.stypox.tridenta.db.data.DbStop
import org.stypox.tridenta.enums.Direction
import org.stypox.tridenta.extractor.ROME_ZONE_ID
import org.stypox.tridenta.repo.data.UiLine
import org.stypox.tridenta.repo.data.UiTrip
import org.stypox.tridenta.util.ZonedDateTimeSerializer
import java.time.ZonedDateTime

@Serializable
sealed interface WidgetState {
    @Serializable
    data class LineTripsAvailable(
        val line: UiLine?,
        val trip: UiTrip?,
        @Serializable(with = ZonedDateTimeSerializer::class)
        val referenceDateTime: ZonedDateTime,
        val tripsInDayCount: Int = 0,
        val tripIndex: Int = 0,
        val prevEnabled: Boolean = false,
        val nextEnabled: Boolean = false,
        val directionFilter: Direction = Direction.ForwardAndBackward,
        val error: Boolean = false,
        val loading: Boolean = true,
        val showPrevStop: Boolean = false,
    ) : WidgetState

    @Serializable
    data class StopTripsAvailable(
        val stop: DbStop? = null,
        val tripIndex: Int = 0,
        val trip: UiTrip? = null,
        val prevEnabled: Boolean = false,
        val nextEnabled: Boolean = false,
        @Serializable(with = ZonedDateTimeSerializer::class)
        val referenceDateTime: ZonedDateTime = ZonedDateTime.now().withZoneSameInstant(ROME_ZONE_ID),
        val loading: Boolean = true,
        val error: Boolean = false,
        val showPrevStop: Boolean = false,
    ) : WidgetState
    @Serializable
    data class Unavailable(val message: String) : WidgetState
}