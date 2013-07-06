package com.keepit.search

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provides, Singleton}
import play.api.Play._
import scala.Some
import java.io.File

trait ResultFeedbackModule extends ScalaModule

case class ProdResultFeedbackModule() extends ResultFeedbackModule {

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

case class DevResultFeedbackModule() extends ResultFeedbackModule {

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
      case Some(_) => ProdResultFeedbackModule().resultClickTracker(s3buffer)
    }
  }
}