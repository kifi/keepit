package com.keepit.model

import com.google.inject.{ Singleton, Provides }
import com.keepit.common.db.slick.Database

case class FakeSliderHistoryTrackerModule() extends SliderHistoryTrackerModule {

  def configure() {}

  @Provides
  @Singleton
  def sliderHistoryTracker(sliderHistoryRepo: SliderHistoryRepo, db: Database): SliderHistoryTracker =
    new SliderHistoryTrackerImpl(sliderHistoryRepo, db, 8, 2, 2)

}
