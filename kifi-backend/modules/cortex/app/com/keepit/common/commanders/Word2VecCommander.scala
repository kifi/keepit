package com.keepit.common.commanders

import com.google.inject.{Inject, Singleton}
import com.keepit.cortex.models.word2vec._
import com.keepit.cortex.utils.MatrixUtils.cosineDistance
import com.keepit.cortex.utils.TextUtils.TextNormalizer
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.cortex.utils.MatrixUtils


@Singleton
class Word2VecCommander @Inject()(
  word2vec: Word2VecWordRepresenter,
  uriFeatureRetriever: Word2VecURIFeatureRetriever
) {
  val (dim, mapper, doc2vec) = {
    (word2vec.dimension, word2vec.mapper, new Doc2Vec(word2vec.mapper, word2vec.dimension))
  }

  def similarity(word1: String, word2: String): Option[Float] = {
    val w1 = word1.toLowerCase.trim
    val w2 = word2.toLowerCase.trim

    if (!mapper.keySet.contains(w1) || !mapper.keySet.contains(w2)){
      None
    } else {
      Some(cosineDistance(mapper(w1), mapper(w2)))
    }
  }

  def similarity(word: String, vec: Array[Float]): Option[Float] = {
    assume(vec.length == dim)
    mapper.get(word).map{ wordVec =>
      cosineDistance(wordVec, vec)
    }
  }

  def getDoc2VecResult(text: String): Option[Doc2VecResult] = {
    val normedText = TextNormalizer.LowerCaseNormalizer.normalize(text)
    doc2vec.sampleBest(normedText, numTry = 6, normalize = true)
  }

  def similarity(uri1: Id[NormalizedURI], uri2: Id[NormalizedURI]): Option[Float] = {
    val v1 = uriFeatureRetriever.getByKey(uri1, word2vec.version)
    val v2 = uriFeatureRetriever.getByKey(uri2, word2vec.version)

    if (v1.isDefined && v2.isDefined){
      Some(cosineDistance(v1.get.vectorize, v2.get.vectorize))
    } else None
  }

  def similarity(uris1: Seq[Id[NormalizedURI]], uris2: Seq[Id[NormalizedURI]]): Option[Float] = {
    val vecs1 = uris1.map{ uri => uriFeatureRetriever.getByKey(uri, word2vec.version)}.flatten.map{_.vectorize}
    val vecs2 = uris2.map{ uri => uriFeatureRetriever.getByKey(uri, word2vec.version)}.flatten.map{_.vectorize}
    if (vecs1.isEmpty || vecs2.isEmpty) return None

    val avg1 = MatrixUtils.average(vecs1.map{MatrixUtils.L2Normalize(_)})
    val avg2 = MatrixUtils.average(vecs2.map{MatrixUtils.L2Normalize(_)})
    Some(MatrixUtils.cosineDistance(avg1, avg2))
  }

  def userSimilarity2(uris1: Seq[Id[NormalizedURI]], uris2: Seq[Id[NormalizedURI]]): Option[Float] = {
    val vecs1 = uris1.map{ uri => uriFeatureRetriever.getByKey(uri, word2vec.version)}.flatten.map{_.vectorize}.map{MatrixUtils.L2Normalize(_)}
    val vecs2 = uris2.map{ uri => uriFeatureRetriever.getByKey(uri, word2vec.version)}.flatten.map{_.vectorize}.map{MatrixUtils.L2Normalize(_)}
    if (vecs1.isEmpty || vecs2.isEmpty) return None

    val sims = vecs1.map{ v =>
      vecs2.map{ w => MatrixUtils.dot(v, w)}
    }.flatten.sum

    Some(sims / (vecs1.size * vecs2.size))
  }

}
