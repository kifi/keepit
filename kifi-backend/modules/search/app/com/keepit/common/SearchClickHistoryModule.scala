package com.keepit.common

import com.keepit.model.ClickHistoryModule
import com.google.inject.{Provides, Singleton}
import com.keepit.search.ClickHistoryBuilder
import play.api.Play._

case class SearchClickHistoryModule() extends ClickHistoryModule {
  def configure() {}

  @Singleton
  @Provides
  def clickHistoryBuilder: ClickHistoryBuilder = {
    val conf = current.configuration.getConfig("click-history-tracker").get
    val filterSize = conf.getInt("filterSize").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val minHits = conf.getInt("minHits").get

    new ClickHistoryBuilder(filterSize, numHashFuncs, minHits)
  }
}
