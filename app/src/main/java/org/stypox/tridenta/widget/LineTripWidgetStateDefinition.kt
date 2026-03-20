package org.stypox.tridenta.widget

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import androidx.glance.state.GlanceStateDefinition
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.stypox.tridenta.log.logError
import java.io.File
import java.io.InputStream
import java.io.OutputStream

object LineTripWidgetStateDefinition : GlanceStateDefinition<WidgetState> {
    private const val DATA_STORE_FILENAME_PREFIX = "line_trip_widget_state_"
    override suspend fun getDataStore(
        context: Context,
        fileKey: String
    ): DataStore<WidgetState> = DataStoreFactory.create(
        serializer = WidgetStateSerializer,
        produceFile = { getLocation(context, fileKey) }
    )

    override fun getLocation(
        context: Context,
        fileKey: String
    ): File =
        context.dataStoreFile(DATA_STORE_FILENAME_PREFIX + fileKey.lowercase())

    object WidgetStateSerializer : Serializer<WidgetState> {
        override suspend fun readFrom(input: InputStream): WidgetState = try {
            Json.decodeFromString(WidgetState.serializer(), input.readBytes().decodeToString())
        } catch (exception: SerializationException) {
            logError(exception.message ?: "Could not read widget state", exception.cause)
            throw CorruptionException("Could not read widget state: ${exception.message}")
        }

        override suspend fun writeTo(
            t: WidgetState,
            output: OutputStream
        ) {
            output.use {
                it.write(Json.encodeToString(WidgetState.serializer(), t).encodeToByteArray())
            }
        }

        override val defaultValue: WidgetState
            get() = WidgetState.Unavailable("Something went wrong")
    }
}