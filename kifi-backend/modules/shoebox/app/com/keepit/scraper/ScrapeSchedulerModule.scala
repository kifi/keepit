package com.keepit.scraper

import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped
import com.google.inject.{Provides, Singleton}
import com.keepit.common.healthcheck.AirbrakeNotifier
import play.api.{Play, Configuration}

trait ScrapeSchedulerModule extends ScalaModule

case class ProdScrapeSchedulerModule() extends ScrapeSchedulerModule {

  def configure {
    bind[ScrapeSchedulerPlugin].to[ScrapeSchedulerPluginImpl].in[AppScoped]
    install(ProdScrapeSchedulerConfigModule())
  }
}

case class DevScrapeSchedulerModule() extends ScrapeSchedulerModule {

  def configure {
    bind[ScrapeSchedulerPlugin].to[ScrapeSchedulerPluginImpl].in[AppScoped]
    install(ProdScrapeSchedulerConfigModule())
  }
}

trait ScrapeSchedulerConfigModule extends ScalaModule {
  protected def conf: Configuration
}

case class ProdScrapeSchedulerConfigModule() extends ScrapeSchedulerConfigModule {
  def configure() {}

  override protected def conf: Configuration = Play.current.configuration

  @Singleton
  @Provides
  def schedulerConfig(): ScraperSchedulerConfig = {
    ScraperSchedulerConfig(
      actorTimeout = conf.getInt("scraper.actorTimeout").get,
      scrapePendingFrequency = conf.getInt("scraper.scrapePendingFrequency").get,
      checkOverdueCountFrequency = conf.getInt("scraper.checkOverdueCountFrequency").get,
      pendingOverdueThreshold = conf.getInt("scraper.pendingOverdueThreshold").get,
      overdueCountThreshold = conf.getInt("scraper.overdueCountThreshold").get
    )
  }
}

case class TestScrapeSchedulerConfigModule() extends ScrapeSchedulerConfigModule {
  def configure() {}

  override protected def conf: Configuration = new Configuration(Configuration.empty.underlying) {
    override def getInt(key: String): Option[Int] = Some(0)
    override def getDouble(key: String): Option[Double] = Some(0)
    override def getBoolean(key: String): Option[Boolean] = Some(true)
  }
}

