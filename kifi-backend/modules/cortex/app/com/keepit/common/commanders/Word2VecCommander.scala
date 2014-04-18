package com.keepit.common.commanders

import com.google.inject.{Inject, Singleton}
import com.keepit.cortex.models.word2vec._
import com.keepit.cortex.utils.MatrixUtils.cosineDistance
import com.keepit.cortex.utils.TextUtils.TextNormalizer


@Singleton
class Word2VecCommander @Inject()(
  word2vec: Word2Vec
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

}
