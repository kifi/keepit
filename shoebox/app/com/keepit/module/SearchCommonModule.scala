package com.keepit.module

import java.io.File

import com.google.inject.Provides
import com.google.inject.Singleton
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.search._
import com.tzavellas.sse.guice.ScalaModule

import play.api.Play.current

class SearchCommonModule extends ScalaModule with Logging {

  def configure() {

  }

  @Singleton
  @Provides
  def searchConfigManager(expRepo: SearchConfigExperimentRepo, db: Database): SearchConfigManager = {
    val optFile = current.configuration.getString("index.config").map(new File(_).getCanonicalFile).filter(_.exists)
    new SearchConfigManager(optFile, expRepo, db)
  }

  @Singleton
  @Provides
  def resultClickTracker: ResultClickTracker = {
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
    ResultClickTracker(dir, numHashFuncs, syncEvery)
  }

  @Singleton
  @Provides
  def clickHistoryTracker: ClickHistoryTracker = {
    val conf = current.configuration.getConfig("click-history-tracker").get
    val filterSize = conf.getInt("filterSize").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val minHits = conf.getInt("minHits").get

    ClickHistoryTracker(filterSize, numHashFuncs, minHits)
  }

  @Singleton
  @Provides
  def browsingHistoryTracker: BrowsingHistoryTracker = {
    val conf = current.configuration.getConfig("browsing-history-tracker").get
    val filterSize = conf.getInt("filterSize").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val minHits = conf.getInt("minHits").get

    BrowsingHistoryTracker(filterSize, numHashFuncs, minHits)
  }

}
