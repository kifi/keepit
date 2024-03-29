package com.keepit.search.tracking

import com.google.inject.{ Provides, Singleton }

case class FakeTrackingModule() extends TrackingModule {

  def configure() {}

  @Provides
  @Singleton
  def resultClickTracker(): ResultClickTracker = {
    val buffer = new InMemoryResultClickTrackerBuffer(1000)
    new ResultClickTracker(new ProbablisticLRU(buffer, 10, 1))
  }

  @Provides @Singleton
  def clickHistoryBuilder(): ClickHistoryBuilder = {
    ClickHistoryBuilder(307, 2, 1)
  }
}
