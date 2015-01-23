package com.keepit.scraper.embedly

import scala.concurrent.Future

class FakeEmbedlyClient extends EmbedlyClient {
  override def embedlyUrl(url: String): String = "https://www.google.com"
  override def getEmbedlyInfo(url: String): Future[Option[EmbedlyInfo]] = Future.successful(None)
}
