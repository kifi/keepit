package com.keepit.scraper

import scala.concurrent._
import com.keepit.model.NormalizedURI

case class FakeScraperModule() extends ScraperModule {
  override def configure() {
    bind[ScraperPlugin].to[FakeScraperPlugin]
  }
}

class FakeScraperPlugin() extends ScraperPlugin {
  def scrape() = Seq()
  def asyncScrape(uri: NormalizedURI) =
    Future.failed(new Exception("Not Implemented"))
}