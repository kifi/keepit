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

object ArticleVersion {
  def zero[A <: Article](implicit kind: ArticleKind[A]): ArticleVersion = ArticleVersion(kind.version, VersionNumber.ZERO)
  def next[A <: Article](previous: Option[ArticleVersion])(implicit kind: ArticleKind[A]): ArticleVersion = {
    previous match {
      case Some(version) if version.major > kind.version => throw new IllegalStateException(s"Version number of $kind must be increasing (current is ${kind.version}, got $previous)")
      case Some(version) if version.major == kind.version => version.copy(minor = version.minor + 1)
      case _ => zero[A]
    }
  }
}

trait ArticleKeyHolder {
  type A <: Article
  def articleKind: ArticleKind[A]
  def uriId: Id[NormalizedURI]
  def bestVersion: Option[ArticleVersion]
  def latestVersion: Option[ArticleVersion]
  def getLatestKey: Option[ArticleKey[A]] = latestVersion.map(toKey)
  def getBestKey: Option[ArticleKey[A]] = bestVersion.map(toKey)
  private def toKey(version: ArticleVersion): ArticleKey[A] = ArticleKey(uriId, articleKind, version)
}

trait ArticleKindHolder {
  val kind: String
  protected val rawKind = ArticleKind.byTypeCode(kind)
  type A = rawKind.article
  def articleKind: ArticleKind[A] = rawKind.self
}

case class BasicArticleInfo(
  isDeleted: Boolean,
  seq: SequenceNumber[BasicArticleInfo],
  uriId: Id[NormalizedURI],
  kind: String, // todo(Léo): make this kind: ArticleKind[_ <: Article] with Scala 2.11, (with proper mapper, serialization is unchanged)
  bestVersion: Option[ArticleVersion],
  latestVersion: Option[ArticleVersion]) extends ArticleKeyHolder with ArticleKindHolder

object BasicArticleInfo {
  implicit val format = (
    (__ \ 'isDeleted).format[Boolean] and
    (__ \ 'seq).format(SequenceNumber.format[BasicArticleInfo]) and
    (__ \ 'uriId).format(Id.format[NormalizedURI]) and
    (__ \ 'kind).format[String] and
    (__ \ 'bestVersion).formatNullable[ArticleVersion] and
    (__ \ 'latestVersion).formatNullable[ArticleVersion]
  )(BasicArticleInfo.apply, unlift(BasicArticleInfo.unapply))
}