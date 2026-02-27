package org.stypox.tridenta.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import dagger.hilt.android.AndroidEntryPoint
import org.stypox.tridenta.db.LineDao
import org.stypox.tridenta.db.StopDao
import org.stypox.tridenta.repo.LineTripsRepository
import org.stypox.tridenta.repo.LinesRepository
import javax.inject.Inject

@AndroidEntryPoint
class MyAppWidgetReceiver : GlanceAppWidgetReceiver() {
    @Inject
    lateinit var stopDao: StopDao

    @Inject
    lateinit var lineDao: LineDao

    @Inject
    lateinit var linesRepository: LinesRepository

    @Inject
    lateinit var tripsRepository: LineTripsRepository

    override val glanceAppWidget: GlanceAppWidget get() = MyAppWidget(stopDao, lineDao,linesRepository,tripsRepository)
}