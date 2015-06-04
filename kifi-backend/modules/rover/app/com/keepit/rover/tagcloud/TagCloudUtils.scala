package com.keepit.rover.tagcloud

import com.keepit.common.queue.messages.SuggestedSearchTerms
import com.keepit.rover.article.{ Article, EmbedlyArticle }
import com.keepit.rover.article.content.{ EmbedlyContent, ArticleContentExtractor }
import scala.collection.mutable

object TagCloudGenerator {

  type WordCounts = Map[String, Int]

  def generate(corpus: LibraryCorpus): SuggestedSearchTerms = {
    val (keywordCnts, entityCnts, docs) = extractKeywordsAndContents(corpus)
    val (twoGrams, threeGrams) = (NGramExtractor.generateNGramsForCorpus(docs, 2), NGramExtractor.generateNGramsForCorpus(docs, 3))

    null
  }

  def extractKeywordsAndContents(corpus: LibraryCorpus): (WordCounts, WordCounts, Seq[String]) = {
    val (keywords, entities, contents) = corpus.articles.values.map { articles =>
      val extractor = ArticleContentExtractor(articles)
      val embedlyArticle: Option[EmbedlyContent] = extractor.getByKind(EmbedlyArticle).asInstanceOf[Option[EmbedlyContent]]
      val keywords = embedlyArticle.map { _.keywords }.get
      val entities = embedlyArticle.map { _.entities }.get.map { _.name }

      val content = extractor.content.getOrElse("")
      (keywords, entities, content)
    }.unzip3

    (wordCount(keywords.flatten), wordCount(entities.flatten), contents.toSeq)
  }

  private def wordCount(tokenStream: Iterable[String]): WordCounts = {
    val wc = mutable.Map[String, Int]().withDefaultValue(0)
    tokenStream.foreach { word =>
      val key = word.toLowerCase
      wc(key) = wc(key) + 1
    }
    wc.toMap
  }

}

object NGramExtractor {
  val punct = """[\\p{Punct}]"""
  val space = """\\s"""
  val stopwords: Set[String] = Set()

  // configs
  val NGRAM_MIN_FREQ = 2 // appears at least 2 times in a single doc

  type WordCounts = Map[String, Int]

  def generateNGramsForCorpus(docs: Seq[String], ngram: Int): WordCounts = {
    val wc = mutable.Map[String, Int]().withDefaultValue(0)
    docs.foreach { doc =>
      generateNGrams(doc, ngram).foreach { case (word, cnt) => wc(word) = wc(word) + cnt }
    }
    wc.toMap
  }

  private def isValidGrams(grams: Seq[String]): Boolean = {
    val gramSet = grams.toSet
    gramSet.size == grams.size && gramSet.intersect(stopwords).isEmpty
  }

  private def generateNGrams(txt: String, ngram: Int): WordCounts = {
    val chunks = getChunks(txt.toLowerCase)
    val wc = mutable.Map[String, Int]().withDefaultValue(0)
    chunks.foreach { chunck =>
      val tokens = chunck.split(space).filter { _.trim.size > 1 }
      tokens.sliding(ngram).foreach { grams =>
        if (grams.size == ngram && isValidGrams(grams)) {
          val gram = grams.mkString(" ")
          wc(gram) = wc(gram) + 1
        }
      }
    }
    wc.filter(_._2 >= NGRAM_MIN_FREQ).toMap
  }

  private def getChunks(txt: String): Seq[String] = {
    txt.split(punct).filter { _.trim != "" }
  }
}
