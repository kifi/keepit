package com.keepit.cortex.article

import com.keepit.rover.article.content.ArticleContentExtractor
import com.keepit.search.Lang

trait CortexArticle {
  def contentLang: Option[Lang]
  def content: String
}

case class BasicCortexArticle(contentLang: Option[Lang], content: String) extends CortexArticle

object BasicCortexArticle {
  def fromArticle(article: com.keepit.search.Article): BasicCortexArticle = {
    BasicCortexArticle(article.contentLang, article.content)
  }

  def fromRoverArticles(articles: Set[com.keepit.rover.article.Article]): BasicCortexArticle = {
    val article = ArticleContentExtractor(articles)
    BasicCortexArticle(article.contentLang, article.content.getOrElse(""))
  }
}
