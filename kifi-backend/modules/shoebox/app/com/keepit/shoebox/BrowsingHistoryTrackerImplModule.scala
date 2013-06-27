package com.keepit.shoebox

import com.google.inject.{Provides, Singleton}
import com.keepit.model.BrowsingHistoryRepo
import com.keepit.common.db.slick.Database
import play.api.Play._

case class BrowsingHistoryTrackerImplModule() extends BrowsingHistoryTrackerModule {

  def configure {}

  @Singleton
  @Provides
  def browsingHistoryTracker(browsingHistoryRepo: BrowsingHistoryRepo, db: Database): BrowsingHistoryTracker = {
    val conf = current.configuration.getConfig("browsing-history-tracker").get
    val filterSize = conf.getInt("filterSize").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val minHits = conf.getInt("minHits").get

    new BrowsingHistoryTrackerImpl(filterSize, numHashFuncs, minHits, browsingHistoryRepo, db)
  }

}
