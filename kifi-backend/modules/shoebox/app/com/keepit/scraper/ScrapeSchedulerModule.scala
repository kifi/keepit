package com.keepit.scraper

import net.codingwell.scalaguice.ScalaModule

trait ScrapeSchedulerModule extends ScalaModule

case class ProdScrapeSchedulerModule() extends ScrapeSchedulerModule {

  def configure {
    bind[ScrapeScheduler].to[ScrapeSchedulerImpl]
    install(ProdScrapeSchedulerConfigModule())
  }

}

case class DevScrapeSchedulerModule() extends ScrapeSchedulerModule {

  def configure {
    bind[ScrapeScheduler].to[ScrapeSchedulerImpl]
    install(ProdScrapeSchedulerConfigModule())
  }
}
