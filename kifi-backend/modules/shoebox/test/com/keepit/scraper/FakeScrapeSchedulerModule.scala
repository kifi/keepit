package com.keepit.scraper

import com.google.inject.{Provides, Singleton}
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.model.NormalizedURI
import com.keepit.scraper.extractor.ExtractorProviderType
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent._

case class FakeScrapeSchedulerModule(fakeArticles: Option[PartialFunction[(String, Option[ExtractorProviderType]), BasicArticle]] = None) extends ScrapeSchedulerModule {
  override def configure() {}

  @Provides @Singleton
  def fakeScrapeScheduler(): ScrapeScheduler = new FakeScrapeScheduler(fakeArticles)
}

class FakeScrapeScheduler(fakeArticles: Option[PartialFunction[(String, Option[ExtractorProviderType]), BasicArticle]]) extends ScrapeScheduler {
  def scheduleScrape(uri: NormalizedURI, date: DateTime)(implicit session: RWSession): Unit = {}
  def scrapeBasicArticle(url: String, extractorProviderType: Option[ExtractorProviderType] = None): Future[Option[BasicArticle]] = Future.successful(fakeArticles.map(_.apply((url, extractorProviderType))))
  def getSignature(url: String, extractorProviderType: Option[ExtractorProviderType] = None): Future[Option[Signature]] = scrapeBasicArticle(url, extractorProviderType).map(_.map(_.signature))
}
