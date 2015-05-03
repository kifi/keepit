package com.keepit.cortex.article

import com.keepit.search.{ Article, Lang }

trait CortexArticle {
  def contentLang: Option[Lang]
  def content: String
}

case class BasicCortexArticle(contentLang: Option[Lang], content: String) extends CortexArticle

object BasicCortexArticle {
  def fromArticle(article: Article): BasicCortexArticle = {
    BasicCortexArticle(article.contentLang, article.content)
  }
}
