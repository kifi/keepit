package com.keepit.scraper

import scala.util.Random

case class ScraperConfig(
  minInterval: Double = 24.0d, //hours
  maxInterval: Double = 1024.0d, //hours
  intervalIncrement: Double = 6.0d, //hours
  intervalDecrement: Double = 2.0d, //hours
  initialBackoff: Double = 3.0d, //hours
  maxBackoff: Double = 1024.0d, //hours
  maxRandomDelay: Int = 600, // seconds
  changeThreshold: Double = 0.5,
  pullMultiplier:Int = sys.props.get("scraper.pull.multiplier") map (_.toInt) getOrElse (8),
  pullFrequency: Int = sys.props.get("scraper.pull.freq") map (_.toInt) getOrElse (5), // seconds
  scrapePendingFrequency: Int = sys.props.get("scraper.pending.freq") map (_.toInt) getOrElse (30), // seconds
  queued: Boolean = sys.props.getOrElse("scraper.plugin.queued", "true").toBoolean,
  async: Boolean = sys.props.getOrElse("scraper.plugin.async", "false").toBoolean,
  actorTimeout: Int = sys.props.getOrElse("scraper.actor.timeout", "20000").toInt,
  syncAwaitTimeout: Int = sys.props.getOrElse("scraper.plugin.sync.await.timeout", "20000").toInt,
  serviceCallTimeout: Int = sys.props.getOrElse("scraper.service.call.timeout", "20000").toInt,
  batchSize: Int = sys.props.getOrElse("scraper.service.batch.size", "10").toInt,
  batchMax: Int = sys.props.getOrElse("scraper.service.batch.max", "50").toInt,
  pendingOverdueThreshold: Int = sys.props.get("scraper.service.pending.overdue.threshold") map (_.toInt) getOrElse (20), // minutes
  pendingSkipThreshold: Int = sys.props.get("scraper.service.pending.skip.threshold") map (_.toInt) getOrElse (5000)
) {

  private[this] val rnd = new Random

  def randomDelay(): Int = rnd.nextInt(maxRandomDelay) // seconds
}

object ScraperConfig {
  val BATCH_SIZE = 100
  val maxContentChars = 100000 // 100K chars
}

