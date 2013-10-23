package com.keepit.search

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provides, Singleton}
import play.api.Play._
import scala.Some
import java.io.File

trait TrackingModule extends ScalaModule {
  @Provides @Singleton
  def browsingHistoryBuilder: BrowsingHistoryBuilder = {
    val conf = current.configuration.getConfig("browsing-history-tracker").get
    val filterSize = conf.getInt("filterSize").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val minHits = conf.getInt("minHits").get
    BrowsingHistoryBuilder(filterSize, numHashFuncs, minHits)
  }

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
    val dirPath = conf.getString("dir").get
    val dir = new File(dirPath).getCanonicalFile()
    if (!dir.exists()) {
      if (!dir.mkdirs()) {
        throw new Exception(s"could not create dir $dir")
      }
    }
    val file = new File(dir, "resultclicks.plru")
    // table size = 16M (physical size = 64MB + 4bytes)
    val buffer = new FileResultClickTrackerBuffer(file, 0x1000000)
    new ResultClickTracker(new ProbablisticLRU(buffer, numHashFuncs, syncEvery)(Some(s3buffer)))
  }
}

case class DevTrackingModule() extends TrackingModule {

  def configure() {}

  @Provides
  @Singleton
  def resultClickTracker(s3buffer: S3BackedResultClickTrackerBuffer): ResultClickTracker = {
    val conf = current.configuration.getConfig("result-click-tracker").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    conf.getString("dir") match {
      case None =>
        val buffer = new InMemoryResultClickTrackerBuffer(1000)
        new ResultClickTracker(new ProbablisticLRU(buffer, numHashFuncs, 1)(Some(s3buffer)))
      case Some(_) => ProdTrackingModule().resultClickTracker(s3buffer)
    }
  }
}