package com.keepit.scraper

import scala.concurrent._
import com.keepit.model.NormalizedURI
import com.google.inject.{Provides, Singleton}
import com.keepit.scraper.extractor.{ExtractorProviderType, Extractor}
import com.keepit.common.db.slick.DBSession.RWSession

case class FakeScrapeSchedulerModule(fakeArticles: Option[PartialFunction[(String, Option[ExtractorProviderType]), BasicArticle]] = None) extends ScrapeSchedulerModule {
  override def configure() {}

  @Provides @Singleton
  def fakeScraperPlugin(): ScrapeSchedulerPlugin = new FakeScrapeSchedulerPlugin(fakeArticles)
}

class FakeScrapeSchedulerPlugin(fakeArticles: Option[PartialFunction[(String, Option[ExtractorProviderType]), BasicArticle]]) extends ScrapeSchedulerPlugin {
  def scrapePending() = Future.successful(Seq())
  def scheduleScrape(uri: NormalizedURI)(implicit session: RWSession): Unit = {}
  def scrapeBasicArticle(url: String, extractorProviderType:Option[ExtractorProviderType] = None): Future[Option[BasicArticle]] = Future.successful(fakeArticles.map(_.apply((url, extractorProviderType))))
}
