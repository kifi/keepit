package com.keepit.search.tracking

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provides, Singleton }
import java.io.File
import com.keepit.common.util.Configuration

trait TrackingModule extends ScalaModule {}

case class ProdTrackingModule() extends TrackingModule {

  def configure() {}

  @Provides @Singleton
  def clickHistoryBuilder(conf: Configuration): ClickHistoryBuilder = {
    val filterSize = conf.getInt("click-history-tracker.filterSize").get
    val numHashFuncs = conf.getInt("click-history-tracker.numHashFuncs").get
    val minHits = conf.getInt("click-history-tracker.minHits").get
    ClickHistoryBuilder(filterSize, numHashFuncs, minHits)
  }

  @Singleton
  @Provides
  def resultClickTracker(s3buffer: S3BackedResultClickTrackerBuffer, conf: Configuration): ResultClickTracker = {
    val numHashFuncs = conf.getInt("result-click-tracker.numHashFuncs").get
    val syncEvery = conf.getInt("result-click-tracker.syncEvery").get
    new ResultClickTracker(new ProbablisticLRU(s3buffer, numHashFuncs, syncEvery))
  }
}

case class DevTrackingModule() extends TrackingModule {

  def configure() {}

  @Provides @Singleton
  def clickHistoryBuilder(conf: Configuration): ClickHistoryBuilder = {
    val filterSize = conf.getInt("click-history-tracker.filterSize").get
    val numHashFuncs = conf.getInt("click-history-tracker.numHashFuncs").get
    val minHits = conf.getInt("click-history-tracker.minHits").get
    ClickHistoryBuilder(filterSize, numHashFuncs, minHits)
  }

  @Provides
  @Singleton
  def resultClickTracker(conf: Configuration): ResultClickTracker = {
    val numHashFuncs = conf.getInt("result-click-tracker.numHashFuncs").get
    val buffer = new InMemoryResultClickTrackerBuffer(1000)
    new ResultClickTracker(new ProbablisticLRU(buffer, numHashFuncs, 1))
  }
}

