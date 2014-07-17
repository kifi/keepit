package com.keepit.scraper

import com.google.inject.Inject
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{ AirbrakeNotifier, SystemAdminMailSender }
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.scraper.extractor.ExtractorProviderType
import org.joda.time.DateTime

import scala.concurrent.Future
import scala.util.Try

class ScrapeSchedulerImpl @Inject() (
  db: Database,
  airbrake: AirbrakeNotifier,
  systemAdminMailSender: SystemAdminMailSender,
  scrapeInfoRepo: ScrapeInfoRepo,
  urlPatternRuleRepo: UrlPatternRuleRepo,
  scraperConfig: ScraperSchedulerConfig,
  scraperClient: ScraperServiceClient) //only on leader
    extends ScrapeScheduler with Logging {

  def scheduleScrape(uri: NormalizedURI, date: DateTime)(implicit session: RWSession): Unit = {
    require(uri != null && !uri.id.isEmpty, "[scheduleScrape] <uri> cannot be null and <uri.id> cannot be empty")
    val uriId = uri.id.get
    if (!NormalizedURIStates.DO_NOT_SCRAPE.contains(uri.state)) {
      val info = scrapeInfoRepo.getByUriId(uriId)
      val toSave = info match {
        case Some(s) => s.state match {
          case ScrapeInfoStates.ACTIVE => s.withNextScrape(date)
          case ScrapeInfoStates.ASSIGNED => s // no change
          case ScrapeInfoStates.INACTIVE => {
            log.warn(s"[scheduleScrape(${uri.toShortString})] scheduling INACTIVE $s")
            s.withState(ScrapeInfoStates.ACTIVE).withNextScrape(date) // dangerous; revisit
          }
        }
        case None => ScrapeInfo(uriId = uriId, nextScrape = date)
      }
      scrapeInfoRepo.save(toSave)
      // todo: It may be nice to force trigger a scrape directly
    }
  }

  @inline private def sanityCheck(url: String): Unit = {
    val parseUriTr = Try(java.net.URI.create(url)) // java.net.URI needed for current impl of HttpFetcher
    require(parseUriTr.isSuccess, s"java.net.URI parser failed to parse url=($url) error=${parseUriTr.failed.get}")
  }

  @inline private def getProxy(url: String): Option[HttpProxy] = db.readOnlyMaster { implicit s => urlPatternRuleRepo.getProxy(url) } // cached; use master

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
