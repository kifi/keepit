package com.keepit.scraper

import scala.util.Random

case class ScraperQueueConfig(
  terminateThreshold: Int,
  queueSizeThreshold: Int,
  pullThreshold: Option[Int],
  terminatorFreq: Int)

case class ScraperHttpConfig(
  httpFetcherEnforcerFreq: Int,
  httpFetcherQSizeThreshold: Int
)

case class ScraperIntervalConfig(
  minInterval: Double, //hours
  maxInterval: Double, //hours
  intervalIncrement: Double, //hours
  intervalDecrement: Double //hours
)

case class ScraperSchedulerConfig(
  actorTimeout: Int,
  scrapePendingFrequency: Int,          // seconds
  checkOverdueCountFrequency: Int,      // minutes
  pendingOverdueThreshold: Int,         // minutes
  overdueCountThreshold: Int
)

case class ScraperConfig(
  intervalConfig: ScraperIntervalConfig,
  initialBackoff: Double, //hours
  maxBackoff: Double, //hours
  maxRandomDelay: Int, // seconds
  changeThreshold: Double,
  pullMultiplier:Int,
  pullFrequency: Int, // seconds
  scrapePendingFrequency: Int, // seconds
  queued: Boolean,
  async: Boolean,
  syncAwaitTimeout: Int,
  serviceCallTimeout: Int,
  batchSize: Int,
  batchMax: Int,
  httpConfig: ScraperHttpConfig,
  queueConfig: ScraperQueueConfig
) {

  private[this] val rnd = new Random

  def randomDelay(): Int = rnd.nextInt(maxRandomDelay) // seconds
}

object ScraperConfig {
  val BATCH_SIZE = 100
  val maxContentChars = 100000 // 100K chars
}

