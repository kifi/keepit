package com.keepit.scraper

import play.api.Configuration

case class FakeScraperConfigModule() extends ScraperConfigModule {

  def configure() {}

  override protected def conf: Configuration = new Configuration(Configuration.empty.underlying) {
    override def getInt(key: String): Option[Int] = Some(0)
    override def getDouble(key: String): Option[Double] = Some(0)
    override def getBoolean(key: String): Option[Boolean] = Some(true)
  }
}
