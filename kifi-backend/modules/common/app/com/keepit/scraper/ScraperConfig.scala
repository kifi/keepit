package com.keepit.scraper

case class ScraperConfig(
  minInterval: Double = 12.0d, //hours
  maxInterval: Double = 1024.0d, //hours
  intervalIncrement: Double = 2.0d, //hours
  intervalDecrement: Double = 1.0d, //hours
  initialBackoff: Double = 1.0d, //hours
  maxBackoff: Double = 1024.0d, //hours
  changeThreshold: Double = 0.05,
  disableScraperService: Boolean = {
    val p = System.getProperty("scraper.service.disable")
    (p != null && p.equalsIgnoreCase("true"))
  },
  batchSize: Int = {
    val p = System.getProperty("scraper.batch.size")
    if (p != null && p.isInstanceOf[Int]) p.toInt else 2
  }
)

object ScraperConfig {
  val BATCH_SIZE = 100

  val maxContentChars = 100000 // 100K chars
}

