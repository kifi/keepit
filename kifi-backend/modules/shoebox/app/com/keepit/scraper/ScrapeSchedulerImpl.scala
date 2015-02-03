package com.keepit.scraper

import com.google.inject.{ Singleton, Provides, Inject }
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{ AirbrakeNotifier, SystemAdminMailSender }
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.model._
import com.keepit.scraper.extractor.ExtractorProviderType
import org.joda.time.DateTime

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

@Singleton
class ScrapeSchedulerImpl @Inject() (
  db: Database,
  airbrake: AirbrakeNotifier,
  systemAdminMailSender: SystemAdminMailSender,
  scrapeInfoRepo: ScrapeInfoRepo,
  urlPatternRules: UrlPatternRulesCommander,
  scraperConfig: ScraperSchedulerConfig,
  scraperClient: ScraperServiceClient,
  implicit val executionContext: ExecutionContext)
    extends ScrapeScheduler with Logging {

  private[this] val scheduleScrapeLock = new ReactiveLock(1)

  def scheduleScrape(uri: NormalizedURI, date: DateTime): Future[Unit] = scheduleScrapeLock.withLock {
    val uriId = uri.id.get
    val parsable = URI.parse(uri.url).isSuccess && Try(java.net.URI.create(uri.toString())).isSuccess
    if (parsable && !NormalizedURIStates.DO_NOT_SCRAPE.contains(uri.state)) db.readWrite { implicit s =>
      val info = scrapeInfoRepo.getByUriId(uriId)
      val toSave = info match {
        case Some(s) => s.state match {
          case ScrapeInfoStates.ACTIVE => s.withNextScrape(date)
          case ScrapeInfoStates.ASSIGNED => s // no change
          case ScrapeInfoStates.INACTIVE => {
            log.warn(s"[scheduleScrape(${uri.toShortString})] scheduling INACTIVE $s")
            s.withState(ScrapeInfoStates.ACTIVE).withNextScrape(date) // todo(Ray): dangerous; revisit
          }
        }
        case None => ScrapeInfo(uriId = uriId, nextScrape = date)
      }
      val saved = scrapeInfoRepo.save(toSave)
      log.info(s"[scheduleScrape] scheduled for ${uri.toShortString}; saved=$saved")
    }
  }

  @inline private def sanityCheck(url: String): Unit = {
    val parseUriTr = Try(java.net.URI.create(url)) // java.net.URI needed for current impl of HttpFetcher
    require(parseUriTr.isSuccess, s"java.net.URI parser failed to parse url=($url) error=${parseUriTr.failed.get}")
  }

  @inline private def getProxy(url: String): Option[HttpProxy] = db.readOnlyMaster { implicit s => urlPatternRules.getProxy(url) } // cached; use master

  def scrapeBasicArticle(url: String, extractorProviderType: Option[ExtractorProviderType]): Future[Option[BasicArticle]] = {
    sanityCheck(url)
    val proxyOpt = getProxy(url)
    log.info(s"[scrapeBasicArticle] invoke (remote) Scraper service; url=$url proxy=$proxyOpt extractorProviderType=$extractorProviderType")
    scraperClient.getBasicArticle(url, proxyOpt, extractorProviderType)
  }

  def getSignature(url: String, extractorProviderType: Option[ExtractorProviderType]): Future[Option[Signature]] = {
    sanityCheck(url)
    val proxyOpt = getProxy(url)
    log.info(s"[getSignature] invoke (remote) Scraper service; url=$url proxy=$proxyOpt extractorProviderType=$extractorProviderType")
    scraperClient.getSignature(url, proxyOpt, extractorProviderType)
  }
}
