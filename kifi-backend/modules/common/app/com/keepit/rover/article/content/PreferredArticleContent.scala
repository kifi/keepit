package com.keepit.rover.article.content

import com.keepit.model.PageAuthor
import com.keepit.rover.article.{DefaultArticle, EmbedlyArticle, ArticleKind, Article}
import org.joda.time.DateTime

object PreferredArticleContent {
  val customArticles = (ArticleKind.all - EmbedlyArticle - DefaultArticle).toSeq
  val defaultPreference: Seq[ArticleKind[_]] = customArticles :+ EmbedlyArticle :+ DefaultArticle
}

case class PreferredArticleContent(articles: Set[Article]) {
  import PreferredArticleContent._

  private val contentByKind: Map[ArticleKind[_], ArticleContent[_]] = articles.map(article => article.kind -> article.content).toMap

  private def collectFirst[T](preference: ArticleKind[_]*)(getValue: ArticleContent[_] => Option[T]): Option[T] = {
    for {
      kind <- preference.toStream ++ (contentByKind.keySet -- preference)
      articleContent <- contentByKind.get(kind)
      value <- getValue(articleContent)
    } yield value
  } headOption

  lazy val title: Option[String] = collectFirst(defaultPreference: _*)(_.title.filter(_.nonEmpty))
  lazy val description: Option[String] = collectFirst(defaultPreference: _*)(_.description.filter(_.nonEmpty))
  lazy val keywords: Set[String] = articles.map(_.content.keywords).flatten
  lazy val authors: Seq[PageAuthor] = collectFirst(defaultPreference: _*)(c => Some(c.authors).filter(_.nonEmpty)) getOrElse Seq.empty
  lazy val content: Option[String] = collectFirst(defaultPreference: _*)(_.description.filter(_.nonEmpty))
  lazy val publishedAt: Option[DateTime] = collectFirst(EmbedlyArticle)(_.publishedAt)

}
