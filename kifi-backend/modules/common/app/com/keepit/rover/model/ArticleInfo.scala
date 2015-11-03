package com.keepit.rover.model

import com.keepit.common.db.{ VersionNumber, SequenceNumber, Id }
import com.keepit.common.time.DateTimeJsonFormat
import com.keepit.model.{ UrlHash, NormalizedURI }
import com.keepit.rover.article.{ ArticleKind, Article }
import com.kifi.macros.json
import play.api.libs.functional.syntax._
import play.api.libs.json._

@json
case class ArticleVersion(major: VersionNumber[Article], minor: VersionNumber[Article]) extends Ordered[ArticleVersion] {
  def compare(that: ArticleVersion) = (major, minor) compare (that.major, that.minor)
  override def toString = s"$major.$minor"
}

case class ArticleKey[A <: Article](urlHash: UrlHash, kind: ArticleKind[A], version: ArticleVersion)

trait ArticleInfoHolder {
  type A <: Article
  def articleKind: ArticleKind[A]
  def url: String
  def urlHash: UrlHash
  def uriId: Option[Id[NormalizedURI]]
  def bestVersion: Option[ArticleVersion]
  def latestVersion: Option[ArticleVersion]
  def getLatestKey: Option[ArticleKey[A]] = latestVersion.map(toKey)
  def getBestKey: Option[ArticleKey[A]] = bestVersion.map(toKey)
  private def toKey(version: ArticleVersion): ArticleKey[A] = ArticleKey(urlHash, articleKind, version)
}

trait ArticleKindHolder {
  val kind: String
  protected val rawKind = ArticleKind.byTypeCode(kind)
  type A = rawKind.article
  def articleKind: ArticleKind[A] = rawKind.self
}

case class ArticleInfo(
  isDeleted: Boolean,
  seq: SequenceNumber[ArticleInfo],
  uriId: Option[Id[NormalizedURI]],
  url: String,
  urlHash: UrlHash,
  kind: String, // todo(Léo): make this kind: ArticleKind[_ <: Article] with Scala 2.11, (with proper mapper, serialization is unchanged)
  bestVersion: Option[ArticleVersion],
  latestVersion: Option[ArticleVersion]) extends ArticleInfoHolder with ArticleKindHolder

object ArticleInfo {
  implicit val format = (
    (__ \ 'isDeleted).format[Boolean] and
    (__ \ 'seq).format(SequenceNumber.format[ArticleInfo]) and
    (__ \ 'uriId).formatNullable(Id.format[NormalizedURI]) and
    (__ \ 'url).format[String] and
    (__ \ 'urlHash).format[UrlHash] and
    (__ \ 'kind).format[String] and
    (__ \ 'bestVersion).formatNullable[ArticleVersion] and
    (__ \ 'latestVersion).formatNullable[ArticleVersion]
  )(ArticleInfo.apply, unlift(ArticleInfo.unapply))
}

