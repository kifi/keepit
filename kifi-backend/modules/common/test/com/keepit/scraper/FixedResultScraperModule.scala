package com.keepit.scraper

import net.codingwell.scalaguice.ScalaModule
import akka.actor.Scheduler
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.scraper.extractor.ExtractorProviderType
import scala.concurrent.Future
import com.keepit.model.HttpProxy
import com.keepit.inject.AppScoped
import com.keepit.model.NormalizedURI
import com.keepit.model.ScrapeInfo
import com.keepit.search.Article
import com.keepit.search.ArticleStore
import com.google.inject.{Singleton, Provides}


case class FixedResultScraperModule() extends ScalaModule {
  def configure(){
    bind[ArticleStore].to[FakeArticleStore].in[AppScoped]
  }

  @Singleton
  @Provides
  def ScraperServiceClient(airbrakeNotifier: AirbrakeNotifier, scheduler: Scheduler): ScraperServiceClient = {
    new FixedResultScraper(airbrakeNotifier, scheduler)
  }
}

case class FixedResultScraper(override val airbrakeNotifier: AirbrakeNotifier, scheduler: Scheduler) extends FakeScraperServiceClientImpl(airbrakeNotifier, scheduler){

  override def getBasicArticle(url: String, proxy: Option[HttpProxy], extractor: Option[ExtractorProviderType]): Future[Option[BasicArticle]] = {
    val content = if (url == "http://fixedResult.com") "fixed result" else "na"
    Future.successful(Some(BasicArticle(title = "not important", content = content, signature = Signature("fixedSignature"), destinationUrl = url)))
  }
}
