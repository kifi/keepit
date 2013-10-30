package com.keepit.scraper

import akka.util.Timeout
import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import com.keepit.model.NormalizedURI
import com.keepit.scraper.extractor.Extractor
import com.keepit.search.Article
import scala.concurrent.Future
import scala.concurrent.duration._

// TODO: remove
class ScraperPluginImpl @Inject() (val schedulingProperties: SchedulingProperties) //only on leader
  extends ScraperPlugin with SchedulingPlugin with Logging {

  override def enabled: Boolean = true
  override def onStart() {
    log.info("starting ScraperPluginImpl")
  }
  override def onStop() {
    log.info("stopping ScraperPluginImpl")
  }

  override def scrapePending(): Future[Seq[(NormalizedURI, Option[Article])]] = ???

  override def asyncScrape(uri: NormalizedURI): Future[(NormalizedURI, Option[Article])] = ???

  override def scrapeBasicArticle(url: String, customExtractor: Option[Extractor] = None): Future[Option[BasicArticle]] = ???

}
