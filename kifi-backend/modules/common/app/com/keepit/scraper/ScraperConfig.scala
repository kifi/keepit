package com.keepit.scraper

import scala.util.Random

case class ScraperConfig(
  minInterval: Double = 24.0d, //hours
  maxInterval: Double = 1024.0d, //hours
  intervalIncrement: Double = 4.0d, //hours
  intervalDecrement: Double = 2.0d, //hours
  initialBackoff: Double = 2.0d, //hours
  maxBackoff: Double = 1024.0d, //hours
  maxRandomDelay: Int = 300, // seconds
  changeThreshold: Double = 0.05,
  scrapePendingFrequency: Int = sys.props.getOrElse("scraper.pending.freq", "10").toInt, // seconds
  async: Boolean = sys.props.getOrElse("scraper.plugin.async", "true").toBoolean,
  actorTimeout: Int = sys.props.getOrElse("scraper.actor.timeout", "20000").toInt,
  syncAwaitTimeout: Int = sys.props.getOrElse("scraper.plugin.sync.await.timeout", "20000").toInt,
  serviceCallTimeout: Int = sys.props.getOrElse("scraper.service.call.timeout", "20000").toInt,
  numInstances: Int = sys.props.getOrElse("scraper.service.instances", (Runtime.getRuntime.availableProcessors * 32).toString).toInt,
  batchSize: Int = sys.props.getOrElse("scraper.service.batch.size", "10").toInt,
  batchMax: Int = sys.props.getOrElse("scraper.service.batch.max", "50").toInt,
  pendingOverdueThreshold: Int = sys.props.getOrElse("scraper.service.pending.overdue.threshold", "3600").toInt,
  pendingSkipThreshold: Int = sys.props.getOrElse("scraper.service.pending.skip.threshold", "500").toInt
) {

  private[this] val rnd = new Random

  def randomDelay(): Int = rnd.nextInt(maxRandomDelay) // seconds
}

object ScraperConfig {
  val BATCH_SIZE = 100
  val maxContentChars = 100000 // 100K chars
}

