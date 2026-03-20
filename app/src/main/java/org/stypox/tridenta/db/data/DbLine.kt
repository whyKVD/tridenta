package org.stypox.tridenta.db.data

import androidx.annotation.ColorInt
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import kotlinx.serialization.Serializable
import org.stypox.tridenta.enums.Area
import org.stypox.tridenta.enums.StopLineType
import org.stypox.tridenta.util.OffsetDateTimeSerializer
import java.time.OffsetDateTime

@Entity(
    primaryKeys = ["lineId", "type"]
)
@Serializable
data class DbLine(
    // some testing exposed that a line is always identified by the (lineId, type) tuple
    val lineId: Int,
    val type: StopLineType,
    val area: Area,
    @ColorInt val color: Int?,
    val longName: String,
    val shortName: String,
    @Ignore val isFavorite: Boolean,
) {
    // needed for Room (@JvmOverloads does not work)
    constructor(
        lineId: Int,
        type: StopLineType,
        area: Area,
        @ColorInt color: Int?,
        longName: String,
        shortName: String,
    ) : this(lineId, type, area, color, longName, shortName, false)
}

@Entity(
    // everything is a primary key since there is no unique key provided by the server
    primaryKeys = ["serviceType", "startDate", "endDate", "header",
        "details", "url", "lineId", "lineType"],
    foreignKeys = [
        ForeignKey(
            entity = DbLine::class,
            parentColumns = ["lineId", "type"],
            childColumns = ["lineId", "lineType"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("lineId", "lineType")
    ]
)
@Serializable
data class DbNewsItem(
    val serviceType: String,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val startDate: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val endDate: OffsetDateTime,
    val header: String,
    val details: String,
    val url: String,
    // this is the foreign key to a line
    val lineId: Int,
    val lineType: StopLineType,
)