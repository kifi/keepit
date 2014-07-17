package com.keepit.scraper

import com.keepit.common.plugin.SchedulingProperties

import scala.concurrent._
import com.keepit.model.NormalizedURI
import com.google.inject.{ Provides, Singleton }
import com.keepit.scraper.extractor.{ ExtractorProviderType, Extractor }
import com.keepit.common.db.slick.DBSession.RWSession
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import org.joda.time.DateTime

case class FakeScraperHealthMonitorModule(fakeArticles: Option[PartialFunction[(String, Option[ExtractorProviderType]), BasicArticle]] = None) extends ScraperHealthMonitorModule {
  override def configure() {}

  @Provides @Singleton
  def fakeScraperPlugin(): ScrapeSchedulerPlugin = new FakeScraperHealthMonitorPlugin(fakeArticles)

  def fakeScraperHealthMonitorPlugin(): ScraperHealthMonitorPlugin = new ScraperHealthMonitorPlugin {
    def scheduling = new SchedulingProperties {
      def enabledOnlyForLeader = false
      def enabled = false
    }
  }
}

class FakeScraperHealthMonitorPlugin(fakeArticles: Option[PartialFunction[(String, Option[ExtractorProviderType]), BasicArticle]]) extends ScrapeSchedulerPlugin {
  def scheduleScrape(uri: NormalizedURI, date: DateTime)(implicit session: RWSession): Unit = {}
  def scrapeBasicArticle(url: String, extractorProviderType: Option[ExtractorProviderType] = None): Future[Option[BasicArticle]] = Future.successful(fakeArticles.map(_.apply((url, extractorProviderType))))
  def getSignature(url: String, extractorProviderType: Option[ExtractorProviderType] = None): Future[Option[Signature]] = scrapeBasicArticle(url, extractorProviderType).map(_.map(_.signature))
}
