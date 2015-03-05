package com.keepit.rover.model

import com.keepit.common.db.{ VersionNumber, SequenceNumber, Id }
import com.keepit.common.time.DateTimeJsonFormat
import com.keepit.model.NormalizedURI
import com.keepit.rover.article.{ ArticleKind, Article }
import com.keepit.rover.store.ArticleKey
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class BasicArticleInfo(
    isDeleted: Boolean,
    seq: SequenceNumber[BasicArticleInfo],
    uriId: Id[NormalizedURI],
    kind: String, // todo(Léo): make this kind: ArticleKind[_ <: Article] with Scala 2.11, (with proper mapper, serialization is unchanged)
    major: Option[VersionNumber[Article]],
    minor: Option[VersionNumber[Article]],
    lastFetchedAt: Option[DateTime]) {
  val articleKind = ArticleKind.byTypeCode(kind)
  def toKey: Option[ArticleKey] = for {
    majorVersion <- major
    minorVersion <- minor
  } yield ArticleKey(uriId, articleKind, majorVersion, minorVersion)
}

object BasicArticleInfo {
  implicit val format = (
    (__ \ 'isDeleted).format[Boolean] and
    (__ \ 'seq).format(SequenceNumber.format[BasicArticleInfo]) and
    (__ \ 'uriId).format(Id.format[NormalizedURI]) and
    (__ \ 'kind).format[String] and
    (__ \ 'major).formatNullable(VersionNumber.format[Article]) and
    (__ \ 'minor).formatNullable(VersionNumber.format[Article]) and
    (__ \ 'lastFetchedAt).formatNullable[DateTime]
  )(BasicArticleInfo.apply, unlift(BasicArticleInfo.unapply))
}