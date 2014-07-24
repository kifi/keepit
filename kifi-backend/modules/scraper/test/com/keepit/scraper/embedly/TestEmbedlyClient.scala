package com.keepit.scraper.embedly

import com.keepit.common.db.Id
import com.keepit.model.{ ImageInfo, NormalizedURI }

import scala.concurrent.Future

class TestEmbedlyClient extends EmbedlyClient {
  override def embedlyUrl(url: String): String = "https://www.google.com"
  override def getEmbedlyInfo(url: String): Future[Option[EmbedlyInfo]] = Future.successful(None)
  override def getImageInfos(uriId: Id[NormalizedURI], url: String): Future[Seq[ImageInfo]] = Future.successful(Seq())
}
