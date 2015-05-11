package com.keepit.rover.article.content

import com.keepit.model.PageAuthor
import com.keepit.rover.article.{ DefaultArticle, EmbedlyArticle, ArticleKind, Article }
import com.keepit.rover.document.tika.KeywordValidator
import com.keepit.rover.document.utils.URITokenizer
import com.keepit.search.{ LangDetector, Lang }
import org.joda.time.DateTime

object ArticleContentExtractor {
  val customArticles = (ArticleKind.all - EmbedlyArticle - DefaultArticle).toSeq
  val defaultPreference: Seq[ArticleKind[_]] = customArticles :+ EmbedlyArticle :+ DefaultArticle
}

case class ArticleContentExtractor(articles: Set[Article]) {
  import ArticleContentExtractor._

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
  lazy val content: Option[String] = collectFirst(defaultPreference: _*)(_.content.filter(_.nonEmpty))
  lazy val publishedAt: Option[DateTime] = collectFirst(EmbedlyArticle)(_.publishedAt)
  lazy val contentType: Option[String] = collectFirst(EmbedlyArticle)(_.contentType.filter(_.nonEmpty))

  lazy val contentLang: Option[Lang] = Seq(content, description).flatten.filter(_.nonEmpty).mkString(" ") match {
    case "" => None
    case content => Some(LangDetector.detect(content))
  }

  lazy val titleLang = title.collect {
    case title if title.nonEmpty =>
      contentLang.map(LangDetector.detect(title, _)) getOrElse LangDetector.detect(title) // bias title language detection using content language
  }

  lazy val validatedKeywords = {
    val urlPhrases = contentByKind.values.map(_.destinationUrl).toSeq.distinct.flatMap(URITokenizer.getTokens)
    val phrases = urlPhrases ++ keywords
    val allPhrases = phrases.foldLeft(phrases) { (currentPhrases, nextPhrase) => currentPhrases ++ KeywordValidator.spaceRegex.split(nextPhrase).filter(_.nonEmpty) }

    val text = phrases ++ title ++ description ++ content

    KeywordValidator.validate(allPhrases, text)
  }

}
