package com.keepit.common.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.cortex.models.word2vec._
import com.keepit.cortex.utils.MatrixUtils._
import com.keepit.cortex.utils.TextUtils.TextTokenizer
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.cortex.utils.TextUtils
import com.keepit.model.Word2VecKeywords
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits._

@Singleton
class Word2VecCommander @Inject() (
    word2vec: Word2VecWordRepresenter,
    uriFeatureRetriever: RichWord2VecURIFeatureRetriever) {

  // a few more than Lucene default stopwords
  val STOP_WORDS = Set("a", "an", "and", "are", "as", "at", "be", "but", "by",
    "for", "if", "in", "into", "is", "it", "no", "not", "of", "on", "or", "such",
    "that", "the", "their", "then", "there", "these", "they", "this", "to", "was",
    "will", "with", "you", "your", "my", "mine", "he", "she", "his", "her")

  val MIN_WORD_COUNT = 20

  val (dim, mapper, doc2vec) = {
    (word2vec.dimension, word2vec.mapper, new Doc2Vec(word2vec.mapper, word2vec.dimension))
  }

  def similarity(word1: String, word2: String): Option[Float] = {
    val w1 = word1.toLowerCase.trim
    val w2 = word2.toLowerCase.trim

    if (!mapper.keySet.contains(w1) || !mapper.keySet.contains(w2)) {
      None
    } else {
      Some(cosineDistance(mapper(w1), mapper(w2)))
    }
  }

  def similarity(word: String, vec: Array[Float]): Option[Float] = {
    assume(vec.length == dim)
    mapper.get(word).map { wordVec =>
      cosineDistance(wordVec, vec)
    }
  }

  def getDoc2VecResult(text: String): Option[Doc2VecResult] = {
    val tokens = TextTokenizer.LowerCaseTokenizer.tokenize(text)
    doc2vec.sampleBest(tokens, numTry = 6, normalize = true)
  }

  def similarity(uri1: Id[NormalizedURI], uri2: Id[NormalizedURI]): Option[Float] = {
    val v1 = uriFeatureRetriever.getByKey(uri1, word2vec.version)
    val v2 = uriFeatureRetriever.getByKey(uri2, word2vec.version)

    if (v1.isDefined && v2.isDefined) {
      Some(cosineDistance(v1.get.vectorize, v2.get.vectorize))
    } else None
  }

  def userUriSimilarity(userUris: Seq[Id[NormalizedURI]], uriId: Id[NormalizedURI]): Map[Id[NormalizedURI], Float] = {
    val uriVec = uriFeatureRetriever.getByKey(uriId, word2vec.version).map { x => L2Normalize(x.vectorize) }
    if (uriVec.isEmpty) return Map()

    val userVecs = userUris.map { uri =>
      uriFeatureRetriever.getByKey(uri, word2vec.version).map { rep =>
        val vec = L2Normalize(rep.vectorize)
        (uri, dot(uriVec.get, vec).toFloat)
      }
    }.flatten

    userVecs.sortBy(-1f * _._2).take(10).toMap
  }

  def feedUserUri(userUris: Seq[Id[NormalizedURI]], feeds: Seq[Id[NormalizedURI]]): Seq[Id[NormalizedURI]] = {

    def scores(feedVec: Array[Float], userVecs: Seq[Array[Float]]): Seq[Float] = {
      userVecs.map { vec => dot(vec, feedVec).toFloat }.filter(_ > 0.7f)
    }

    val userVecs = userUris.map { uri => uriFeatureRetriever.getByKey(uri, word2vec.version) }.flatten.map { x => L2Normalize(x.vectorize) }
    val scoredUris = feeds.flatMap { uri =>
      uriFeatureRetriever.getByKey(uri, word2vec.version).flatMap { rep =>
        val vec = L2Normalize(rep.vectorize)
        val scrs = scores(vec, userVecs).sortBy(x => -1f * x)
        if (scrs.size > 5) Some((uri, scrs.take(5).sum)) else None
      }
    }

    scoredUris.sortBy(-1f * _._2).map { _._1 }
  }

  def similarity(uris1: Seq[Id[NormalizedURI]], uris2: Seq[Id[NormalizedURI]]): Option[Float] = {
    val vecs1 = uris1.map { uri => uriFeatureRetriever.getByKey(uri, word2vec.version) }.flatten.map { _.vectorize }
    val vecs2 = uris2.map { uri => uriFeatureRetriever.getByKey(uri, word2vec.version) }.flatten.map { _.vectorize }
    if (vecs1.isEmpty || vecs2.isEmpty) return None

    val avg1 = average(vecs1.map { L2Normalize(_) })
    val avg2 = average(vecs2.map { L2Normalize(_) })
    Some(cosineDistance(avg1, avg2))
  }

  def userSimilarity2(uris1: Seq[Id[NormalizedURI]], uris2: Seq[Id[NormalizedURI]]): Option[Float] = {
    val vecs1 = uris1.map { uri => uriFeatureRetriever.getByKey(uri, word2vec.version) }.flatten.map { x => L2Normalize(x.vectorize) }
    val vecs2 = uris2.map { uri => uriFeatureRetriever.getByKey(uri, word2vec.version) }.flatten.map { x => L2Normalize(x.vectorize) }
    if (vecs1.size < 20 || vecs2.size < 20) return None

    val (shorter, longer) = if (vecs1.size < vecs2.size) (vecs1, vecs2) else (vecs2, vecs1)
    val indexes = (0 until longer.size).toSet
    var matched = Set[Int]()

    val scores = shorter.map { vec =>
      val (idx, score) = (indexes -- matched).map { idx =>
        (idx, dot(vec, longer(idx)))
      }.toArray.sortBy(-1f * _._2).head
      matched = matched + idx
      score
    }

    Some(scores.sum / scores.length)
  }

  def similarity(query: String, uri: Id[NormalizedURI]): Option[Float] = {
    val vecs = TextUtils.TextTokenizer.LowerCaseTokenizer.tokenize(query).flatMap { word2vec.apply(_) }.map { x => x.vectorize }
    if (vecs.isEmpty) return None

    val queryVec = vecs.reduce(add)
    val docVec = uriFeatureRetriever.getByKey(uri, word2vec.version).map { _.vectorize }

    docVec.map { doc =>
      cosineDistance(doc, queryVec)
    }

  }

  private def extractKeywords(feat: RichWord2VecURIFeature): Word2VecKeywords = {
    val count = feat.bagOfWords.map { _._2 }.sum
    val hasEnoughWords = count > MIN_WORD_COUNT

    if (!hasEnoughWords) return Word2VecKeywords(Seq(), Seq(), 0)

    val cosineKeywords = feat.keywords.filter(!STOP_WORDS.contains(_))
    val freqKeywords = feat.bagOfWords.toArray.sortBy(-1 * _._2).map { _._1 }.filter(!STOP_WORDS.contains(_)).take(5)
    Word2VecKeywords(cosineKeywords, freqKeywords, count)
  }

  def uriKeywords(uri: Id[NormalizedURI]): Option[Word2VecKeywords] = {
    uriFeatureRetriever.getByKey(uri, word2vec.version).map { feat =>
      extractKeywords(feat)
    }
  }

  def batchURIKeywords(uris: Seq[Id[NormalizedURI]]): Future[Seq[Option[Word2VecKeywords]]] = {
    val featsFut = Future { uriFeatureRetriever.getByKeys(uris, word2vec.version) }
    featsFut.map { feats =>
      feats.map { ftOpt => ftOpt.map { extractKeywords(_) } }
    }
  }
}
