package com.keepit.scraper

import scala.concurrent._
import com.keepit.model.NormalizedURI
import com.google.inject.{Provides, Singleton}

case class FakeScraperModule(fakeArticles: Option[PartialFunction[String, BasicArticle]] = None) extends ScraperModule {
  override def configure() {}

  @Provides @Singleton
  def fakeScraperPlugin(): ScraperPlugin = new FakeScraperPlugin(fakeArticles)
}

class FakeScraperPlugin(fakeArticles: Option[PartialFunction[String, BasicArticle]]) extends ScraperPlugin {
  def scrapePending() = Future.successful(Seq())
  def asyncScrape(uri: NormalizedURI) =
    Future.failed(new Exception("Not Implemented"))
  def scrapeBasicArticle(url: String): Future[Option[BasicArticle]] =
    Future.successful(fakeArticles.map(_.apply(url)))
}