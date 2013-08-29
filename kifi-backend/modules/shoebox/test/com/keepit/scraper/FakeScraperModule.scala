package com.keepit.scraper

import scala.concurrent._
import com.keepit.model.NormalizedURI
import com.google.inject.{Provides, Singleton}
import com.keepit.scraper.extractor.Extractor

case class FakeScraperModule(fakeArticles: Option[PartialFunction[(String, Option[Extractor]), BasicArticle]] = None) extends ScraperModule {
  override def configure() {}

  @Provides @Singleton
  def fakeScraperPlugin(): ScraperPlugin = new FakeScraperPlugin(fakeArticles)
}

class FakeScraperPlugin(fakeArticles: Option[PartialFunction[(String, Option[Extractor]), BasicArticle]]) extends ScraperPlugin {
  def scrapePending() = Future.successful(Seq())
  def asyncScrape(uri: NormalizedURI) =
    Future.failed(new Exception("Not Implemented"))
  def scrapeBasicArticle(url: String, customExtractor: Option[Extractor] = None): Future[Option[BasicArticle]] =
    Future.successful(fakeArticles.map(_.apply((url, customExtractor))))
}
