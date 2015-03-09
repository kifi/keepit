package com.keepit.scraper

import com.keepit.rover.fetcher.ScraperHttpConfig

case class ScraperQueueConfig(
  terminateThreshold: Int,
  queueSizeThreshold: Int,
  pullThreshold: Option[Int],
  terminatorFreq: Int)

case class ScraperConfig(
    changeThreshold: Double,
    pullMultiplier: Int,
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
  val pullMax = numCores * pullMultiplier
  val pullThreshold = numCores // tweak
  val numWorkers = numCores * math.max(pullMultiplier / 2, 2) // tweak
}

object ScraperConfig {
  val BATCH_SIZE = 100
  val maxContentChars = 100000 // 100K chars
}

