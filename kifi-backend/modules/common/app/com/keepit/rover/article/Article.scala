package com.keepit.rover.article

import com.keepit.model.PageAuthor
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
