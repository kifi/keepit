package com.keepit.rover.document.utils

import com.keepit.rover.article.Article
import com.keepit.rover.document.tika.KeywordValidator

object ArticleKeywordExtractor {
  def apply(article: Article): Seq[String] = {
    val urlPhrases = Seq(article.url, article.content.destinationUrl).distinct.flatMap(URITokenizer.getTokens)
    val contentPhrases = article.content.keywords
    val phrases = urlPhrases ++ contentPhrases
    val allPhrases = phrases.foldLeft(phrases) { (currentPhrases, nextPhrase) => currentPhrases ++ KeywordValidator.spaceRegex.split(nextPhrase).filter { _.length > 0 }.toSeq }

    val text = urlPhrases ++ contentPhrases ++ article.content.title ++ article.content.description ++ article.content.content

    KeywordValidator.validate(allPhrases, text)
  }
}
