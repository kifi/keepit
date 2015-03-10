package com.keepit.scraper

import com.keepit.rover.fetcher.ScraperHttpConfig

case class ScraperQueueConfig(
  terminateThreshold: Int,
  queueSizeThreshold: Int,
  pullThreshold: Option[Int],
  terminatorFreq: Int)

case class ScraperConfig(
    changeThreshold: Double,
    pullFrequency: Int, // seconds
    queued: Boolean,
    async: Boolean,
    syncAwaitTimeout: Int,
    serviceCallTimeout: Int,
    batchSize: Int,
    batchMax: Int,
    httpConfig: ScraperHttpConfig,
    queueConfig: ScraperQueueConfig) {
  val numCores = Runtime.getRuntime.availableProcessors
  val pullThreshold = 4 // tweak
  val numWorkers = 16
}

object ScraperConfig {
  val BATCH_SIZE = 100
  val maxContentChars = 100000 // 100K chars
}

