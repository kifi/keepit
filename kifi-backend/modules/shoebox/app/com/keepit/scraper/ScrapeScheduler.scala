package com.keepit.scraper

import com.keepit.rover.article.Signature

import scala.concurrent.Future
import com.keepit.model.NormalizedURI
import com.keepit.scraper.extractor.ExtractorProviderType
import com.keepit.common.db.slick.DBSession.RWSession
import org.joda.time.DateTime
import com.keepit.common.time._

trait ScrapeScheduler {
  def scheduleScrape(uri: NormalizedURI, date: DateTime = currentDateTime)(implicit session: RWSession): Unit
  def scrapeBasicArticle(url: String, extractorProviderType: Option[ExtractorProviderType]): Future[Option[BasicArticle]] // todo: move out
  def getSignature(url: String, extractorProviderType: Option[ExtractorProviderType]): Future[Option[Signature]]
}
