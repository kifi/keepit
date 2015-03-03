package com.keepit.rover.article

import org.joda.time.DateTime
import play.api.libs.json._
import com.keepit.common.reflection.CompanionTypeSystem

sealed trait Article { self =>
  type A >: self.type <: Article
  protected def kind: ArticleKind[A]
  protected def instance: A = self

  def url: String
  def createdAt: DateTime
  def content: ArticleContent
}

sealed trait ArticleKind[A <: Article] {
  implicit def kind: ArticleKind[A] = this

  // Serialization helpers
  def typeCode: String
  def version: Int
  implicit final def format: Format[A] = formatByVersion(version)
  def formatByVersion(thatVersion: Int): Format[A]
}

object Article {
  implicit val format = new Format[Article] {
    def writes(article: Article) = {
      Json.obj(
        "kind" -> article.kind.typeCode,
        "version" -> article.kind.version,
        "article" -> article.kind.format.writes(article.instance)
      )
    }
    def reads(json: JsValue) = for {
      typeCode <- (json \ "kind").validate[String]
      version <- (json \ "version").validate[Int]
      article <- ArticleKind.byTypeCode(typeCode).formatByVersion(version).reads(json \ "article")
    } yield article
  }
}

object ArticleKind {
  val all: Set[ArticleKind[_ <: Article]] = CompanionTypeSystem[Article, ArticleKind[_ <: Article]]("A")
  val byTypeCode: Map[String, ArticleKind[_ <: Article]] = {
    require(all.size == all.map(_.typeCode).size, "Duplicate Article type codes.")
    all.map { articleKind => articleKind.typeCode -> articleKind }.toMap
  }
}

case class UnknownArticleVersionException[A <: Article](kind: ArticleKind[A], currentVersion: Int, unknownVersion: Int)
  extends Throwable(s"[$kind] Unknown version: $unknownVersion (Latest version: $currentVersion)")

case class EmbedlyArticle(url: String, createdAt: DateTime, json: JsValue) extends Article {
  type A = EmbedlyArticle
  def kind = EmbedlyArticle
  lazy val content = new EmbedlyContent(json)
}

case object EmbedlyArticle extends ArticleKind[EmbedlyArticle] {
  val typeCode = "embedly"
  val version = 1
  def formatByVersion(thatVersion: Int) = thatVersion match {
    case `version` => Json.format[EmbedlyArticle]
    case _ => throw new UnknownArticleVersionException(this, version, thatVersion)
  }
}

case class DefaultArticle(
    createdAt: DateTime,
    url: String,
    content: DefaultContent) extends Article {
  type A = DefaultArticle
  def kind = DefaultArticle
}

case object DefaultArticle extends ArticleKind[DefaultArticle] {
  val typeCode = "default"
  val version = 1
  def formatByVersion(thatVersion: Int) = thatVersion match {
    case `version` => Json.format[DefaultArticle]
    case _ => throw new UnknownArticleVersionException(this, version, thatVersion)
  }
}

case class YoutubeArticle(
    createdAt: DateTime,
    url: String,
    content: YoutubeContent) extends Article {
  type A = YoutubeArticle
  def kind = YoutubeArticle
}

case object YoutubeArticle extends ArticleKind[YoutubeArticle] {
  val typeCode = "youtube"
  val version = 1
  def formatByVersion(thatVersion: Int) = thatVersion match {
    case `version` => Json.format[YoutubeArticle]
    case _ => throw new UnknownArticleVersionException(this, version, thatVersion)
  }
}

