package com.keepit.scraper

import com.google.inject.{ Singleton, Provides, Inject }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{ AirbrakeNotifier, SystemAdminMailSender }
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.rover.document.utils.Signature
import com.keepit.scraper.extractor.ExtractorProviderType
import org.joda.time.DateTime

import scala.concurrent.Future
import scala.util.Try

@Singleton
class ScrapeSchedulerImpl @Inject() (
  db: Database,
  airbrake: AirbrakeNotifier,
  systemAdminMailSender: SystemAdminMailSender,
  scrapeInfoRepo: ScrapeInfoRepo,
  urlPatternRules: UrlPatternRulesCommander,
  scraperConfig: ScraperSchedulerConfig,
  scraperClient: ScraperServiceClient) //only on leader
    extends ScrapeScheduler with Logging {

  def scheduleScrape(uri: NormalizedURI, date: DateTime)(implicit session: RWSession): Unit = scrapeInfoRepo.scheduleScrape(uri, date)

  @inline private def sanityCheck(url: String): Boolean = {
    val parseUriTr = Try(java.net.URI.create(url)) // java.net.URI needed for current impl of HttpFetcher
    if (parseUriTr.isFailure) log.warn(s"java.net.URI parser failed to parse url=($url) error=${parseUriTr.failed.get}")
    parseUriTr.isSuccess
  }

  @inline private def getProxy(url: String): Option[HttpProxy] = db.readOnlyMaster { implicit s => urlPatternRules.getProxy(url) } // cached; use master

  def scrapeBasicArticle(url: String, extractorProviderType: Option[ExtractorProviderType]): Future[Option[BasicArticle]] = {
    if (!sanityCheck(url)) Future.successful(None)
    else {
      val proxyOpt = getProxy(url)
      log.info(s"[scrapeBasicArticle] invoke (remote) Scraper service; url=$url proxy=$proxyOpt extractorProviderType=$extractorProviderType")
      scraperClient.getBasicArticle(url, proxyOpt, extractorProviderType)
    }
  }

  def getSignature(url: String, extractorProviderType: Option[ExtractorProviderType]): Future[Option[Signature]] = {
    if (!sanityCheck(url)) Future.successful(None)
    else {
      val proxyOpt = getProxy(url)
      log.info(s"[getSignature] invoke (remote) Scraper service; url=$url proxy=$proxyOpt extractorProviderType=$extractorProviderType")
      scraperClient.getSignature(url, proxyOpt, extractorProviderType)
    }
  }
}
