package com.keepit.cortex.models.word2vec

import com.keepit.cortex.core.StatModel
import com.keepit.cortex.core.BinaryFormatter

trait Word2Vec extends StatModel

case class Word2VecImpl(dimension: Int, mapper: Map[String, Array[Float]]) extends Word2Vec

object Word2VecFormatter extends BinaryFormatter[Word2Vec]{
  def toBinary(word2vec: Word2Vec): Array[Byte] = ???
  def fromBinary(bytes: Array[Byte]): Word2Vec = {
    val reader = new Word2VecReader()
    val (dim, mapper) = reader.fromBinary(bytes)
    Word2VecImpl(dim, mapper)
  }
}

