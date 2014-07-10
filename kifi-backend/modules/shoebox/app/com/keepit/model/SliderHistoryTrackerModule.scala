package com.keepit.model

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.db.slick.Database
import play.api.Play._
import net.codingwell.scalaguice.ScalaModule

trait SliderHistoryTrackerModule extends ScalaModule

case class ProdSliderHistoryTrackerModule() extends SliderHistoryTrackerModule {
  def configure {}

  @Singleton
  @Provides
  def sliderHistoryTrackerImpl(sliderHistoryRepo: SliderHistoryRepo, db: Database): SliderHistoryTracker = {
    val conf = current.configuration.getConfig("slider-history-tracker").get
    val filterSize = conf.getInt("filterSize").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val minHits = conf.getInt("minHits").get

    new SliderHistoryTrackerImpl(sliderHistoryRepo, db, filterSize, numHashFuncs, minHits)
  }
}
