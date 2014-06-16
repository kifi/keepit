package com.keepit.scraper

import scala.util.Random
import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped
import com.google.inject.{Provides, Singleton}
import com.keepit.common.healthcheck.AirbrakeNotifier
import play.api.{Play, Configuration}

case class ScraperIntervalConfig(
  initialBackoff: Double,     //hours
  maxBackoff: Double,         //hours
  maxRandomDelay: Int,        // seconds
  minInterval: Double,        //hours
  maxInterval: Double,        //hours
  intervalIncrement: Double,  //hours
  intervalDecrement: Double   //hours
)

case class ScraperSchedulerConfig(
  actorTimeout: Int,
  scrapePendingFrequency: Int,          // seconds
  checkOverdueCountFrequency: Int,      // minutes
  pendingOverdueThreshold: Int,         // minutes
  overdueCountThreshold: Int,
  intervalConfig: ScraperIntervalConfig
) {
  private[this] val rnd = new Random
  def randomDelay(): Int = rnd.nextInt(intervalConfig.maxRandomDelay)     // seconds
}

trait ScrapeSchedulerConfigModule extends ScalaModule {
  protected def conf: Configuration

  @Singleton
  @Provides
  def intervalConfig(): ScraperIntervalConfig = {

    ScraperIntervalConfig(
      initialBackoff = conf.getDouble("scraper.initialBackoff").get, //hours
      maxBackoff = conf.getDouble("scraper.maxBackoff").get, //hours
      maxRandomDelay = conf.getInt("scraper.maxRandomDelay").get, // seconds
      minInterval = conf.getDouble("scraper.interval.min").get, //hours
      maxInterval = conf.getDouble("scraper.interval.max").get, //hours
      intervalIncrement = conf.getDouble("scraper.interval.increment").get, //hours
      intervalDecrement = conf.getDouble("scraper.interval.decrement").get //hours
    )

  }

  @Singleton
  @Provides
  def schedulerConfig(intervalConfig: ScraperIntervalConfig): ScraperSchedulerConfig = {

    new ScraperSchedulerConfig(
      actorTimeout = conf.getInt("scraper.actorTimeout").get,
      scrapePendingFrequency = conf.getInt("scraper.scrapePendingFrequency").get,
      checkOverdueCountFrequency = conf.getInt("scraper.checkOverdueCountFrequency").get,
      pendingOverdueThreshold = conf.getInt("scraper.pendingOverdueThreshold").get,
      overdueCountThreshold = conf.getInt("scraper.overdueCountThreshold").get,
      intervalConfig = intervalConfig
    )

  }
}

case class ProdScrapeSchedulerConfigModule() extends ScrapeSchedulerConfigModule {
  def configure() {}
  override protected def conf: Configuration = Play.current.configuration
}

case class TestScrapeSchedulerConfigModule() extends ScrapeSchedulerConfigModule {
  def configure() {}

  override protected def conf: Configuration = new Configuration(Configuration.empty.underlying) {
    override def getInt(key: String): Option[Int] = Some(0)
    override def getDouble(key: String): Option[Double] = Some(0)
    override def getBoolean(key: String): Option[Boolean] = Some(true)
  }
}

