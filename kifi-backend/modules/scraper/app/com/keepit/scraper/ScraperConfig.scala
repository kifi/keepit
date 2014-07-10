package com.keepit.scraper

import scala.util.Random

case class ScraperQueueConfig(
  terminateThreshold: Int,
  queueSizeThreshold: Int,
  pullThreshold: Option[Int],
  terminatorFreq: Int)

case class ScraperHttpConfig(
  httpFetcherEnforcerFreq: Int,
  httpFetcherQSizeThreshold: Int)

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
  queueConfig: ScraperQueueConfig)

object ScraperConfig {
  val BATCH_SIZE = 100
  val maxContentChars = 100000 // 100K chars
}

