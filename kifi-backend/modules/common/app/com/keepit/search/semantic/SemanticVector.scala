package com.keepit.search.semantic

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.util.BytesRef
import scala.collection.mutable
import scala.util.Random
import java.util.Arrays

class SemanticVector(val bytes: Array[Byte]) extends AnyVal {

    // hamming distance
  def distance(other: SemanticVector) = SemanticVector.distance(bytes, other.bytes)

  // similarity of two vectors (-1.0 to 1.0) ~ cosine distance
  def similarity(other: SemanticVector) = SemanticVector.similarity(bytes, other.bytes)
  def similarity(data: Array[Byte], offset: Int) = SemanticVector.similarity(bytes, data, offset)

  def set(data: Array[Byte], offset: Int, length: Int) = System.arraycopy(data, offset, bytes, 0, length)

  def toBinary = bytes.map{ x => String.format("%8s",  (x & 0xFF).toBinaryString).replace(' ', '0') }.mkString(" ")
}

object SemanticVector {
  class Sketch(val vec: Array[Float]) extends AnyVal {
    def copyFrom(other: Sketch): Unit = Array.copy(vec, 0, other.vec, 0, vectorSize)
    def clone() = new Sketch(vec.clone())
    def clear(): Unit = Arrays.fill(vec, 0.0f)
  }

  val vectorSize = 128 // bits
  val arraySize = vectorSize / 8
  private[this] val gaussianSampleSize = 100000L
  private[this] val gaussianSample = {
    val rnd = new Random(123456)
    val arr = new Array[Float](gaussianSampleSize.toInt)
    var i = 0
    while (i < arr.length) {
      arr(i) = rnd.nextGaussian().toFloat
      i += 1
    }
    Arrays.sort(arr)
    arr
  }
  private[this] val similarityScore = {
    val half = vectorSize.toFloat / 2.0f
    (0 to vectorSize).map{ distance => 1.0f - (distance.toFloat / half) }.toArray
  }

  // hamming distance
  def distance(v1: Array[Byte], v2: Array[Byte], v2offset: Int = 0) = {
    var i = 0
    var dist = 0
    while (i < arraySize) {
      dist += popCount((v1(i) ^ v2(i + v2offset)) & 0xFF)
      i += 1
    }
    dist
  }

  // similarity of two vectors (-1.0 to 1.0) ~ cosine distance
  def similarity(v1: Array[Byte], v2: Array[Byte], v2offset: Int = 0) = similarityScore(distance(v1, v2, v2offset))

  private[this] val popCount = {
    val arr = new Array[Int](256)
    var i = 0
    while (i < 256) {
      arr(i) = Integer.bitCount(i)
      i += 1
    }
    arr
  }

  private[this] val MASK = 0x7FFFFFFFFFFFFFFFL // 63 bits

  def getSeed(term: String): Sketch = {
    var seed = (term.hashCode.toLong + (term.charAt(0).toLong << 31)) & MASK
    val sketch = emptySketch
    var i = 0
    while (i < vectorSize) {
      seed = (seed * 0x5DEECE66DL + 0x123456789L) & MASK // linear congruential generator
      sketch.vec(i) = gaussianSample((seed % gaussianSampleSize).toInt)
      i += 1
    }
    sketch
  }

  def emptySketch = new Sketch(new Array[Float](vectorSize))

  def updateSketch(sketch1: Sketch, sketch2: Sketch) {
    updateSketch(sketch1, sketch2, 1.0f)
  }

  def updateSketch(sketch1: Sketch, sketch2: Sketch, norm: Float) {
    var i = 0
    while (i < vectorSize) {
      sketch1.vec(i) += (sketch2.vec(i) * norm)
      i += 1
    }
  }

  def vectorize(sketch: Sketch) = {
    val vector = new Array[Byte](arraySize)
    var i = 0
    var j = 0
    var byte = 0
    while (i < vectorSize) {
      byte = (byte << 1) | (if (sketch.vec(i) > 0.0f) 1 else 0)
      i += 1
      if ((i % 8) == 0) {
        vector(j) = byte.toByte
        byte = 0
        j += 1
      }
    }
    new SemanticVector(vector)
  }

  def vectorize(counts: Array[Int], threshold: Int) = {
    val vector = new Array[Byte](arraySize)
    var i = 0
    var j = 0
    var byte = 0
    while (i < vectorSize) {
      byte = (byte << 1) | (if (counts(i) > threshold) 1 else 0)
      i += 1
      if ((i % 8) == 0) {
        vector(j) = byte.toByte
        byte = 0
        j += 1
      }
    }
    new SemanticVector(vector)
  }
}

class SemanticVectorBuilder(windowSize: Int) {
  import SemanticVector._

  private[this] var termSketches = Map.empty[String, Sketch]
  private[this] var termSeeds = Map.empty[String, Sketch]

  private[this] val termQueue = new Array[String](windowSize)
  private[this] val seedQueue = new Array[Sketch](windowSize)
  private[this] val activeTermPos = mutable.Map[String, Int]()

  private[this] var headPos = 0
  private[this] var tailPos = - windowSize
  private[this] var midPos = - (windowSize / 2)
  private[this] var headSketch = emptySketch
  private[this] var tailSketch = emptySketch

  def load(tokenStream: TokenStream) {
    val termAttr = tokenStream.getAttribute(classOf[CharTermAttribute])

    try {
      tokenStream.reset()
      while (tokenStream.incrementToken()) if (termAttr.length > 0) add(new String(termAttr.buffer, 0, termAttr.length))
      tokenStream.end()
    } finally {
      tokenStream.close()
    }
    flush
  }

  def clear {
    headPos = 0
    tailPos = - windowSize
    midPos = - (windowSize / 2)
    headSketch.clear()
    tailSketch.clear()
  }

  def resetTermSketches {
    termSketches = Map.empty[String, Sketch]
  }

  def getSeedSketch(term: String) = {
    termSeeds.get(term) match {
      case Some(sketch) => sketch
      case None =>
        val sketch = getSeed(term)
        termSeeds = termSeeds + (term -> sketch)
        sketch
    }
  }

  def midSketch = {
    val midSeed = seedQueue(midPos % windowSize)
    val sketch = headSketch.clone

    var i = 0
    while (i < vectorSize) {
      sketch.vec(i) -= (midSeed.vec(i) * 0.95f + tailSketch.vec(i))
      i += 1
    }
    sketch
  }

  def add(term: String) {
    if (midPos >= 0) updateTermSketch(termQueue(midPos % windowSize), midSketch)
    if (tailPos >= 0) updateSketch(tailSketch, seedQueue(tailPos % windowSize))

    val seed = getSeedSketch(term)

    activeTermPos.get(term) match {
      case Some(oldPos) => // a duplicate term, update the position and clear the old seed, do not update headSketch
        if (oldPos > 0 && oldPos > tailPos) seedQueue(oldPos % windowSize).clear()
      case None => // not a duplicate term, update headSketch, drop the tail term
        updateSketch(headSketch, seed)
        if (tailPos >= 0) activeTermPos -= termQueue(tailPos % windowSize)
    }

    activeTermPos(term) = headPos
    termQueue(headPos % windowSize) = term
    seedQueue(headPos % windowSize) = seed

    headPos += 1
    midPos += 1
    tailPos += 1
  }

  def flush {
    while (midPos < headPos) {
      if (midPos >= 0) updateTermSketch(termQueue(midPos % windowSize), midSketch)
      if (tailPos >= 0) updateSketch(tailSketch, seedQueue(tailPos % windowSize))
      midPos += 1
      tailPos += 1
    }
    clear
  }

  def updateTermSketch(token: String, localSketch: Sketch) {
    val sketch = termSketches.get(token) match {
      case Some(sketch) => sketch
      case None =>
        val sketch = getSeed(token).clone
        termSketches += (token -> sketch)
        sketch
    }
    updateSketch(sketch, localSketch)
  }

  def aggregatedVector = vectorize(headSketch)

  def tokenStream() = {
    new TokenStream {
      val termAttr = addAttribute(classOf[CharTermAttribute])
      val payloadAttr = addAttribute(classOf[PayloadAttribute])
      val posIncrAttr = addAttribute(classOf[PositionIncrementAttribute])
      private val iterator = termSketches.iterator

      override def incrementToken(): Boolean = {
        if (iterator.hasNext) {
          clearAttributes()
          val (termText, sketch) = iterator.next
          payloadAttr.setPayload(new BytesRef(vectorize(sketch).bytes))
          termAttr.append(termText)
          posIncrAttr.setPositionIncrement(1)
          true
        } else {
          false
        }
      }
    }
  }
}

class SemanticVectorComposer {
  import SemanticVector._

  private[this] var cnt = 0
  private[this] val counters = new Array[Int](vectorSize)

  def add(sv: SemanticVector, weight: Int): SemanticVectorComposer = add(sv.bytes, 0, sv.bytes.length, weight)

  def add(vector: Array[Byte], offset: Int, length: Int, weight: Int): SemanticVectorComposer = {
    var i = 0
    var b = 0
    while (i < length) {
      val b = vector(i + offset)
      val j = i * 8
      counters(j    ) += ((b >> 7) & 0x1) * weight
      counters(j + 1) += ((b >> 6) & 0x1) * weight
      counters(j + 2) += ((b >> 5) & 0x1) * weight
      counters(j + 3) += ((b >> 4) & 0x1) * weight
      counters(j + 4) += ((b >> 3) & 0x1) * weight
      counters(j + 5) += ((b >> 2) & 0x1) * weight
      counters(j + 6) += ((b >> 1) & 0x1) * weight
      counters(j + 7) += ((b     ) & 0x1) * weight
      i += 1
    }
    cnt += weight
    this
  }

  def getSemanticVector() = vectorize(counters, cnt / 2)

  def getQuasiSketch() = {
    val sketch = emptySketch
    var i = 0
    while (i < vectorSize) {
      sketch.vec(i) = ((counters(i).toFloat/cnt.toFloat) - 0.5f)
      i += 1
    }
    sketch
  }

  def getCount(i: Int): Int = counters(i)

  def numInputs = cnt
}
