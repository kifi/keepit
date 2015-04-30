package com.keepit.rover.document.tika

import com.keepit.rover.article.Article
import com.keepit.rover.document.utils.URITokenizer

object ArticleKeywordExtractor {
  def apply(article: Article): Seq[String] = {
    val urlPhrases = Seq(article.url, article.content.destinationUrl).distinct.flatMap(URITokenizer.getTokens)
    val contentPhrases = article.content.keywords
    val phrases = urlPhrases ++ contentPhrases
    val allPhrases = phrases.foldLeft(phrases) { (currentPhrases, nextPhrase) => currentPhrases ++ KeywordValidator.spaceRegex.split(nextPhrase).filter { _.length > 0 }.toSeq }

    val validator = new KeywordValidator(allPhrases)

    @inline def processAndBreak(token: String): Unit = {
      validator.characters(token.toCharArray)
      validator.break()
    }

    validator.startDocument()

    urlPhrases.foreach(processAndBreak) // URL Keywords

    contentPhrases.foreach(processAndBreak) // Content Keywords

    article.content.title.foreach(processAndBreak) // Title

    article.content.description.foreach(processAndBreak) // Description

    article.content.content.foreach(processAndBreak) // Content

    validator.endDocument()

    validator.keywords
  }
}
