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
  def kind: String
  def articleKind = ArticleKind.byTypeCode(kind)
  def uriId: Id[NormalizedURI]
  def bestVersion: Option[ArticleVersion]
  def latestVersion: Option[ArticleVersion]
  def getLatestKey[A <: Article](implicit kind: ArticleKind[A]): Option[ArticleKey[A]] = latestVersion.map(toKey(_))
  def getBestKey[A <: Article](implicit kind: ArticleKind[A]): Option[ArticleKey[A]] = bestVersion.map(toKey(_))
  private def toKey[A <: Article](version: ArticleVersion)(implicit kind: ArticleKind[A]) = ArticleKey(uriId, kind, version)
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