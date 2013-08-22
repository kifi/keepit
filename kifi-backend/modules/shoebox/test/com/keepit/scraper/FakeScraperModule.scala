package com.keepit.scraper

import scala.concurrent._
import com.keepit.model.NormalizedURI
import com.google.inject.{Provides, Singleton}

case class FakeScraperModule(fakeSignatures: Option[PartialFunction[String, Signature]] = None) extends ScraperModule {
  override def configure() {}

  @Provides @Singleton
  def fakeScraperPlugin(): ScraperPlugin = new FakeScraperPlugin(fakeSignatures)
}

class FakeScraperPlugin(fakeSignatures: Option[PartialFunction[String, Signature]]) extends ScraperPlugin {
  def scrapePending() = Future.successful(Seq())
  def asyncScrape(uri: NormalizedURI) =
    Future.failed(new Exception("Not Implemented"))
  def asyncSignature(url: String): Future[Option[Signature]] =
    Future.successful(fakeSignatures.map(_.apply(url)))
}