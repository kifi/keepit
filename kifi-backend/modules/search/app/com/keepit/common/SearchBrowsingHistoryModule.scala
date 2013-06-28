package com.keepit.common

import com.google.inject.{Provides, Singleton}
import com.keepit.search.BrowsingHistoryBuilder
import play.api.Play._
import com.keepit.model.BrowsingHistoryModule

case class SearchBrowsingHistoryModule() extends BrowsingHistoryModule {
  def configure() {}

  @Singleton
  @Provides
  def browsingHistoryBuilder: BrowsingHistoryBuilder = {
    val conf = current.configuration.getConfig("browsing-history-tracker").get
    val filterSize = conf.getInt("filterSize").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val minHits = conf.getInt("minHits").get

    new BrowsingHistoryBuilder(filterSize, numHashFuncs, minHits)
  }
}
