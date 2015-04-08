package com.keepit.rover.model

import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.model.NormalizedURI
import com.keepit.rover.article.content.{ NormalizationInfo, HttpInfo }
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.json.Json

@json
case class ShoeboxArticleUpdate(
  uriId: Id[NormalizedURI],
  kind: String,
  url: String,
  createdAt: DateTime,
  title: Option[String],
  sensitive: Boolean,
  httpInfo: Option[HttpInfo],
  normalizationInfo: Option[NormalizationInfo]) extends ArticleKindHolder

case class ShoeboxArticleUpdates(updates: Seq[ShoeboxArticleUpdate], maxSeq: SequenceNumber[ArticleInfo])

object ShoeboxArticleUpdates {
  implicit val format = {
    implicit val seqFormat = SequenceNumber.format[ArticleInfo]
    Json.format[ShoeboxArticleUpdates]
  }
}
