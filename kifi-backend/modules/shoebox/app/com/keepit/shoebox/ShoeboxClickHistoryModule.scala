package com.keepit.shoebox

import com.google.inject.{Provides, Singleton}
import com.keepit.model.{ClickHistoryModule, ClickHistoryRepo}
import com.keepit.common.db.slick.Database
import play.api.Play._

case class ShoeboxClickHistoryModule() extends ClickHistoryModule {

  def configure {}

  @Singleton
  @Provides
  def clickHistoryTracker(repo: ClickHistoryRepo, db: Database): ClickHistoryTracker = {
    val conf = current.configuration.getConfig("click-history-tracker").get
    val filterSize = conf.getInt("filterSize").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val minHits = conf.getInt("minHits").get

    new ClickHistoryTrackerImpl(filterSize, numHashFuncs, minHits, repo, db)
  }

}
