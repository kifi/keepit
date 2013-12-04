package com.keepit.scraper

case class ScraperConfig(
  minInterval: Double = 12.0d, //hours
  maxInterval: Double = 1024.0d, //hours
  intervalIncrement: Double = 4.0d, //hours
  intervalDecrement: Double = 2.0d, //hours
  initialBackoff: Double = 2.0d, //hours
  maxBackoff: Double = 1024.0d, //hours
  changeThreshold: Double = 0.05,
  scrapePendingFrequency: Int = sys.props.getOrElse("scraper.pending.freq", "30").toInt,
  disableScraperService: Boolean = sys.props.getOrElse("scraper.service.disable", "false").toBoolean,
  batchSize: Int = sys.props.getOrElse("scraper.service.batch.size", "10").toInt,
  batchMax: Int = sys.props.getOrElse("scraper.service.batch.max", "200").toInt,
  pendingOverdueThreshold: Int = sys.props.getOrElse("scraper.service.pending.overdue.threshold", "3600").toInt,
  pendingSkipThreshold: Int = sys.props.getOrElse("scraper.service.pending.skip.threshold", "200").toInt
)

object ScraperConfig {
  val BATCH_SIZE = 100
  val maxContentChars = 100000 // 100K chars
}

