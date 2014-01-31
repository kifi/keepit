package com.keepit.scraper

import play.api.Plugin
import scala.concurrent.Future
import com.keepit.model.NormalizedURI
import com.keepit.scraper.extractor.{ExtractorProviderType}
import com.keepit.common.db.slick.DBSession.RWSession

trait ScrapeSchedulerPlugin extends Plugin {
  def scheduleScrape(uri: NormalizedURI, delayMillis: Int = 0)(implicit session: RWSession): Unit
  def scrapeBasicArticle(url: String, extractorProviderType:Option[ExtractorProviderType]): Future[Option[BasicArticle]] // todo: move out
  def getSignature(url: String, extractorProviderType: Option[ExtractorProviderType]): Future[Option[Signature]]
}
