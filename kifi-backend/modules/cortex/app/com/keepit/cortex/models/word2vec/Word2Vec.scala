package com.keepit.cortex.models.word2vec

import com.keepit.cortex.core.{BinaryFormatter, FeatureRepresentation, StatModel}
import com.keepit.cortex.features.Document
import java.io._
import com.keepit.cortex.core.BinaryFeatureFormatter
import com.keepit.model.NormalizedURI

trait Word2Vec extends StatModel {
  val dimension: Int
  val mapper: Map[String, Array[Float]]
}

case class Word2VecImpl(val dimension: Int, val mapper: Map[String, Array[Float]]) extends Word2Vec

object Word2VecFormatter extends BinaryFormatter[Word2Vec]{
  def toBinary(word2vec: Word2Vec): Array[Byte] = {
    val writer = new Word2VecWriter()
    writer.toBinary(word2vec.dimension, word2vec.mapper)
  }
  def fromBinary(bytes: Array[Byte]): Word2Vec = {
    val reader = new Word2VecReader()
    val (dim, mapper) = reader.fromBinary(bytes)
    Word2VecImpl(dim, mapper)
  }
}

case class RichWord2VecURIFeature(
  dim: Int,
  vec: Array[Float],
  keywords: Array[String],
  bagOfWords: Map[String, Int]
) extends FeatureRepresentation[NormalizedURI, Word2Vec] {
  override def vectorize: Array[Float] = vec
}

object RichWord2VecURIFeatureFormatter extends BinaryFeatureFormatter[RichWord2VecURIFeature] {
  def toBinary(feat: RichWord2VecURIFeature): Array[Byte] = {
    RichWord2VecURIFeatureFormat.toBinary(feat)
  }

  def fromBinary(bytes: Array[Byte]): RichWord2VecURIFeature = {
    RichWord2VecURIFeatureFormat.fromBinary(bytes)
  }
}


object RichWord2VecURIFeatureFormat {
  private val FLOAT_SIZE = 4
  private val INT_SIZE = 4
  private val CHAR_SIZE = 2
  private val SEP = '\t'

  private def numOfBytes(feat: RichWord2VecURIFeature): Int = {
    val intSize = 1 * INT_SIZE + feat.bagOfWords.size * INT_SIZE
    val floatSize = feat.dim * FLOAT_SIZE
    val keywordSize = (feat.keywords.map{_.length}.sum + feat.keywords.size) * CHAR_SIZE   // keyword1 SEP keyword2 SEP ... keywordn SEP
    val bagOfWordSize = (feat.bagOfWords.map{ case (token, cnt) => token.length}.sum + feat.bagOfWords.size) * CHAR_SIZE + feat.bagOfWords.size * INT_SIZE

    intSize + floatSize + keywordSize + bagOfWordSize
  }

  def toBinary(feat: RichWord2VecURIFeature): Array[Byte] = {
    val bs = new ByteArrayOutputStream(numOfBytes(feat))
    val os = new DataOutputStream(bs)
    os.writeInt(feat.dim)
    feat.vec.foreach{os.writeFloat(_)}

    os.writeInt(feat.keywords.size)
    feat.keywords.foreach{ word =>
      word.toCharArray().foreach{os.writeChar(_)}
      os.writeChar(SEP)
    }

    os.writeInt(feat.bagOfWords.size)
    feat.bagOfWords.map{ case (word, count) =>
      os.writeInt(count)
    }

    feat.bagOfWords.map{ case (word, count) =>
      word.toCharArray().foreach{os.writeChar(_)}
      os.writeChar(SEP)
    }

    os.close()
    val rv = bs.toByteArray()
    bs.close()
    rv
  }

  def fromBinary(bytes: Array[Byte]): RichWord2VecURIFeature = {
    val is = new DataInputStream(new ByteArrayInputStream(bytes))

    val dim = is.readInt()
    val arr = new Array[Float](dim)
    var i = 0
    while (i < dim){
      arr(i) = is.readFloat()
      i += 1
    }

    val numKeyWords = is.readInt()
    i = 0
    val builder = new StringBuilder()
    var ch = ' '
    while( i < numKeyWords){
      ch = is.readChar()
      if (ch == SEP) i += 1
      builder.append(ch)
    }

    val keywords = if (builder.isEmpty) Array[String]() else builder.toString.split(SEP)

    val sizeBagOfWords = is.readInt()
    val counts = new Array[Int](sizeBagOfWords)
    i = 0
    while (i < sizeBagOfWords){
      counts(i) = is.readInt()
      i += 1
    }

    val builder2 = new StringBuilder()
    ch = ' '
    i = 0
    while ( i < sizeBagOfWords){
      ch = is.readChar()
      if (ch == SEP) i += 1
      builder2.append(ch)
    }
    val bow = if (builder2.isEmpty) Array[String]() else builder2.toString.split(SEP)

    RichWord2VecURIFeature(dim, arr, keywords, (bow zip counts).toMap)
  }
}
