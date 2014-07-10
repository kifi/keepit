package com.keepit.search.tracker

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provides, Singleton }
import play.api.Play._
import java.io.File

trait TrackingModule extends ScalaModule {

  @Provides @Singleton
  def clickHistoryBuilder: ClickHistoryBuilder = {
    val conf = current.configuration.getConfig("click-history-tracker").get
    val filterSize = conf.getInt("filterSize").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val minHits = conf.getInt("minHits").get
    ClickHistoryBuilder(filterSize, numHashFuncs, minHits)
  }
}

case class ProdTrackingModule() extends TrackingModule {

  def configure() {}

  @Singleton
  @Provides
  def resultClickTracker(s3buffer: S3BackedResultClickTrackerBuffer): ResultClickTracker = {
    val conf = current.configuration.getConfig("result-click-tracker").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val syncEvery = conf.getInt("syncEvery").get
    new ResultClickTracker(new ProbablisticLRU(s3buffer, numHashFuncs, syncEvery))
  }
}

case class DevTrackingModule() extends TrackingModule {

  def configure() {}

  @Provides
  @Singleton
  def resultClickTracker(): ResultClickTracker = {
    val conf = current.configuration.getConfig("result-click-tracker").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val buffer = new InMemoryResultClickTrackerBuffer(1000)
    new ResultClickTracker(new ProbablisticLRU(buffer, numHashFuncs, 1))
  }
}
