package com.keepit.rover.article

import com.keepit.model.PageAuthor
import com.keepit.scraper.embedly.{ EmbedlyEntity, EmbedlyKeyword, EmbedlyImage }
import org.joda.time.DateTime
import play.api.libs.json._
import com.keepit.common.reflection.CompanionTypeSystem

sealed trait Article { self =>
  type A >: self.type <: Article
  protected def kind: ArticleKind[A]
  protected def instance: A = self

  def createdAt: DateTime
  def url: String
  def destinationUrl: String

  def title: Option[String]
  def description: Option[String]
  def content: Option[String]
  def keywords: Seq[String]
  def authors: Seq[PageAuthor]
  def publishedAt(): Option[DateTime]
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

case class EmbedlyArticle(createdAt: DateTime, json: JsValue) extends Article {
  type A = EmbedlyArticle
  def kind = EmbedlyArticle

  def url = (json \ "original_url").as[String]
  def destinationUrl = (json \ "url").as[String]

  def title = (json \ "title").asOpt[String]
  def description = (json \ "description").asOpt[String]
  def rawContent = (json \ "content").asOpt[String]
  def content = rawContent // todo(Léo): needs post-processing

  def authors = (json \ "authors").asOpt[Seq[PageAuthor]] getOrElse Seq.empty[PageAuthor]
  def publishedAt = (json \ "published").asOpt[DateTime]
  def isSafe = (json \ "safe").asOpt[Boolean]
  def language = (json \ "language").asOpt[String]
  def images = (json \ "images").as[Seq[EmbedlyImage]]
  def embedlyKeywords = (json \ "keywords").as[Seq[EmbedlyKeyword]]
  def keywords = embedlyKeywords.map(_.name)
  def entities = (json \ "entities").as[Seq[EmbedlyEntity]]
  def faviconUrl = (json \ "favicon_url").asOpt[String]
}

case object EmbedlyArticle extends ArticleKind[EmbedlyArticle] {
  val typeCode = "embedly"
  val version = 1
  def formatByVersion(thatVersion: Int) = thatVersion match {
    case `version` => Json.format[EmbedlyArticle]
    case _ => throw new UnknownArticleVersionException(this, version, thatVersion)
  }
}
