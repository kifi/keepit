package com.keepit.rover.tagcloud

import com.keepit.common.queue.messages.SuggestedSearchTerms
import com.keepit.rover.article.{ Article, EmbedlyArticle }
import com.keepit.rover.article.content.{ EmbedlyContent, ArticleContentExtractor }
import scala.collection.mutable

object TagCloudGenerator {

  import WordCountHelper.{ wordCount, topKcounts }
  import NGramHelper._

  val TOP_K = 50

  def generate(corpus: LibraryCorpus): SuggestedSearchTerms = {
    val (keywordCnts, entityCnts, docs) = extractFromCorpus(corpus)
    val (twoGrams, threeGrams) = (NGramHelper.generateNGramsForCorpus(docs, 2), NGramHelper.generateNGramsForCorpus(docs, 3))
    val multiGrams = combineGrams(twoGrams, threeGrams)
    val multiGramsIndex = buildMultiGramIndex(multiGrams.keySet)

    val oneGramEntities: WordCounts = (keywordCnts.keySet.intersect(entityCnts.keySet)).map { k => k -> (keywordCnts(k) max entityCnts(k)) }.toMap

    val keyGrams: WordCounts = topKcounts(keywordCnts, TOP_K).keys.map { key =>
      multiGramsIndex.getOrElse(key, Set()).map { gram => (gram, multiGrams(gram)) }.take(3) // find superString that contain the one-gram keyword, and return freq with them
    }.flatten.toMap

    val result = topKcounts(oneGramEntities ++ keyGrams, TOP_K)
    SuggestedSearchTerms(result.map { case (w, c) => (w, c * 1f) })
  }

  // return: EmbedlyKeywords, EmbedlyEnitites, ArticleContents
  def extractFromCorpus(corpus: LibraryCorpus): (WordCounts, WordCounts, Seq[String]) = {
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

}

object WordCountHelper {
  type WordCounts = Map[String, Int]

  def wordCount(tokenStream: Iterable[String]): WordCounts = {
    val wc = mutable.Map[String, Int]().withDefaultValue(0)
    tokenStream.foreach { word =>
      val key = word.toLowerCase
      wc(key) = wc(key) + 1
    }
    wc.toMap
  }

  def topKcounts(wordCounts: WordCounts, topK: Int): WordCounts = {
    wordCounts.toArray.sortBy(-_._2).take(topK).toMap
  }
}

object NGramHelper {
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

  def getChunks(txt: String): Seq[String] = {
    txt.split(punct).filter { _.trim != "" }
  }

  def buildMultiGramIndex(multigrams: Set[String]): Map[String, Set[String]] = {
    val index = mutable.Map[String, Set[String]]().withDefaultValue(Set())
    multigrams.foreach { gram =>
      gram.split(" ").foreach { token =>
        index(token) = index(token) + gram
      }
    }
    index.toMap
  }

  // if any two gram is 'likely' to be substring of a three gram, drop the 2-gram, pick the 3-gram
  def combineGrams(twoGrams: WordCounts, threeGrams: WordCounts): WordCounts = {
    val index = buildMultiGramIndex(threeGrams.keySet)
    val toAdd = mutable.Map[String, Int]()
    val toDrop = mutable.Set[String]()
    twoGrams.keys.foreach { twoGram =>
      val parts = twoGram.split(" ")
      assert(parts.size >= 2)
      val candidates = index(parts(0)).intersect(index(parts(1))).filter { x => x.contains(twoGram) }
      candidates.map { cand => cand -> threeGrams(cand) }
        .filter {
          case (cand, m) =>
            val n = twoGrams(twoGram)
            // heursitc: if there are enought sample to suggest that the two gram appears to occur with the 3 gram, 2 gram is not complete. need to be replaced
            m >= 5 && (m * 1.0 / n >= 0.6)
        }.toArray.sortBy(-_._2)
        .headOption.foreach { case (threeGram, cnt) => toAdd(threeGram) = cnt; toDrop.add(twoGram) }
    }

    twoGrams.filter { case (word, cnt) => !toDrop.contains(word) } ++ toAdd
  }
}
