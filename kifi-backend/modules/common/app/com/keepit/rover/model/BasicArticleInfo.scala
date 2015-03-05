package com.keepit.rover.model

import com.keepit.common.db.{ VersionNumber, SequenceNumber, Id }
import com.keepit.common.time.DateTimeJsonFormat
import com.keepit.model.NormalizedURI
import com.keepit.rover.article.{ ArticleKind, Article }
import com.keepit.rover.store.ArticleKey
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

@json
case class ArticleVersion(major: VersionNumber[Article], minor: VersionNumber[Article]) extends Ordered[ArticleVersion] {
  def compare(that: ArticleVersion) = {
    val majorComparison = (major compare that.major)
    if (majorComparison == 0) { minor compare that.minor } else majorComparison
  }
  override def toString = s"$major.$minor"
}

trait ArticleKeyHolder {
  def uriId: Id[NormalizedURI]
  def kind: String
  def bestVersion: Option[ArticleVersion]
  def latestVersion: Option[ArticleVersion]
  def articleKind = ArticleKind.byTypeCode(kind)
  def getLatestKey: Option[ArticleKey] = bestVersion.map(toKey)
  def getBestKey: Option[ArticleKey] = latestVersion.map(toKey)
  private def toKey(version: ArticleVersion) = ArticleKey(uriId, articleKind, version)
}

case class BasicArticleInfo(
  isDeleted: Boolean,
  seq: SequenceNumber[BasicArticleInfo],
  uriId: Id[NormalizedURI],
  kind: String, // todo(Léo): make this kind: ArticleKind[_ <: Article] with Scala 2.11, (with proper mapper, serialization is unchanged)
  bestVersion: Option[ArticleVersion],
  latestVersion: Option[ArticleVersion],
  lastFetchedAt: Option[DateTime]) extends ArticleKeyHolder

object BasicArticleInfo {
  implicit val format = (
    (__ \ 'isDeleted).format[Boolean] and
    (__ \ 'seq).format(SequenceNumber.format[BasicArticleInfo]) and
    (__ \ 'uriId).format(Id.format[NormalizedURI]) and
    (__ \ 'kind).format[String] and
    (__ \ 'bestVersion).formatNullable[ArticleVersion] and
    (__ \ 'latestVersion).formatNullable[ArticleVersion] and
    (__ \ 'lastFetchedAt).formatNullable[DateTime]
  )(BasicArticleInfo.apply, unlift(BasicArticleInfo.unapply))
}