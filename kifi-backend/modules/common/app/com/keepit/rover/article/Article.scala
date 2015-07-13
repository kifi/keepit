package com.keepit.rover.article

import com.keepit.common.db.VersionNumber
import com.keepit.rover.article.content._
import org.joda.time.DateTime
import play.api.libs.json._
import com.keepit.common.reflection.CompanionTypeSystem
import play.api.mvc.PathBindable

sealed trait ArticleKind[A <: Article] {
  final type article = A
  implicit def self: ArticleKind[article] = this

  // Serialization helpers
  def typeCode: String
  def version: VersionNumber[Article] // todo(Léo): make VersionNumber[A] with Scala 2.11
  implicit final def format: Format[article] = formatByVersion(version)
  def formatByVersion(thatVersion: VersionNumber[Article]): Format[article]
}

object ArticleKind {
  val all: Set[ArticleKind[_ <: Article]] = CompanionTypeSystem[Article, ArticleKind[_ <: Article]]("A")
  val byTypeCode: Map[String, ArticleKind[_ <: Article]] = {
    require(all.size == all.map(_.typeCode).size, "Duplicate Article type codes.")
    all.map { articleKind => articleKind.typeCode -> articleKind }.toMap
  }

  implicit val format: Format[ArticleKind[_ <: Article]] = new Format[ArticleKind[_ <: Article]] {
    def reads(json: JsValue) = json.validate[String].map(ArticleKind.byTypeCode)
    def writes(kind: ArticleKind[_ <: Article]) = JsString(kind.typeCode)
  }

  implicit def pathBinder(implicit stringBinder: PathBindable[String]) = new PathBindable[ArticleKind[_ <: Article]] {
    override def bind(key: String, value: String): Either[String, ArticleKind[_ <: Article]] =
      stringBinder.bind(key, value) match {
        case Right(typeCode) => byTypeCode.get(typeCode) match {
          case Some(kind) => Right(kind)
          case None => Left(s"Unknown ArticleKind: $typeCode")
        }
        case _ => Left("Unable to bind an ArticleKind")
      }
    override def unbind(key: String, kind: ArticleKind[_ <: Article]): String = kind.typeCode
  }
}

sealed trait Article { self =>
  type A >: self.type <: Article
  val kind: ArticleKind[A]
  def instance: A = self
  def asExpected[B <: Article](implicit expectedKind: ArticleKind[B]): B = {
    if (kind == expectedKind) self.asInstanceOf[B]
    else throw UnexpectedArticleKindException(self, expectedKind)
  }

  def url: String
  def createdAt: DateTime
  def content: ArticleContent[A]
}

object Article {
  implicit val format = new Format[Article] {
    def writes(article: Article) = {
      Json.obj(
        "kind" -> article.kind,
        "version" -> article.kind.version,
        "article" -> article.kind.format.writes(article.instance)
      )
    }
    def reads(json: JsValue) = for {
      typeCode <- (json \ "kind").validate[String]
      version <- (json \ "version").validate[VersionNumber[Article]]
      article <- ArticleKind.byTypeCode(typeCode).formatByVersion(version).reads(json \ "article")
    } yield article
  }
}

case class UnknownArticleVersionException[A <: Article](kind: ArticleKind[A], currentVersion: VersionNumber[Article], unknownVersion: VersionNumber[Article])
  extends Exception(s"[$kind] Unknown version: $unknownVersion (Latest version: $currentVersion)")

case class UnexpectedArticleKindException[A <: Article, B <: Article](article: A, expectedKind: ArticleKind[B])
  extends Exception(s"${article.kind} does not match expected kind $expectedKind: $article")

case class EmbedlyArticle(url: String, createdAt: DateTime, content: EmbedlyContent) extends Article {
  type A = EmbedlyArticle
  val kind = EmbedlyArticle
}

case object EmbedlyArticle extends ArticleKind[EmbedlyArticle] {
  val typeCode = "embedly"
  val version = VersionNumber[Article](1)
  def formatByVersion(thatVersion: VersionNumber[Article]) = thatVersion match {
    case `version` => Json.format[EmbedlyArticle]
    case _ => throw new UnknownArticleVersionException(this, version, thatVersion)
  }
}

case class DefaultArticle(
    createdAt: DateTime,
    url: String,
    content: DefaultContent) extends Article {
  type A = DefaultArticle
  val kind = DefaultArticle
}

case object DefaultArticle extends ArticleKind[DefaultArticle] {
  val typeCode = "default"
  val version = VersionNumber[Article](1)
  def formatByVersion(thatVersion: VersionNumber[Article]) = thatVersion match {
    case `version` => Json.format[DefaultArticle]
    case _ => throw new UnknownArticleVersionException(this, version, thatVersion)
  }
}

case class YoutubeArticle(
    createdAt: DateTime,
    url: String,
    content: YoutubeContent) extends Article {
  type A = YoutubeArticle
  val kind = YoutubeArticle
}

case object YoutubeArticle extends ArticleKind[YoutubeArticle] {
  val typeCode = "youtube"
  val version = VersionNumber[Article](1)
  def formatByVersion(thatVersion: VersionNumber[Article]) = thatVersion match {
    case `version` => Json.format[YoutubeArticle]
    case _ => throw new UnknownArticleVersionException(this, version, thatVersion)
  }
}

case class GithubArticle(
    createdAt: DateTime,
    url: String,
    content: GithubContent) extends Article {
  type A = GithubArticle
  val kind = GithubArticle
}

case object GithubArticle extends ArticleKind[GithubArticle] {
  val typeCode = "github"
  val version = VersionNumber[Article](1)
  def formatByVersion(thatVersion: VersionNumber[Article]) = thatVersion match {
    case `version` => Json.format[GithubArticle]
    case _ => throw new UnknownArticleVersionException(this, version, thatVersion)
  }
}

case class LinkedInProfileArticle(
    createdAt: DateTime,
    url: String,
    content: LinkedInProfileContent) extends Article {
  type A = LinkedInProfileArticle
  val kind = LinkedInProfileArticle
}

case object LinkedInProfileArticle extends ArticleKind[LinkedInProfileArticle] {
  val typeCode = "linked_in_profile"
  val version = VersionNumber[Article](2)
  def formatByVersion(thatVersion: VersionNumber[Article]) = thatVersion match {
    case `version` => Json.format[LinkedInProfileArticle]
    case _ => throw new UnknownArticleVersionException(this, version, thatVersion)
  }
}
