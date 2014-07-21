package com.keepit.search.semantic

import scala.util.Random
import java.util.Arrays

class SemanticVector(val bytes: Array[Byte]) extends AnyVal {

  // hamming distance
  def distance(other: SemanticVector) = SemanticVector.distance(bytes, other.bytes)

  // similarity of two vectors (-1.0 to 1.0) ~ cosine distance
  def similarity(other: SemanticVector) = SemanticVector.similarity(bytes, other.bytes)
  def similarity(data: Array[Byte], offset: Int) = SemanticVector.similarity(bytes, data, offset)

  def set(data: Array[Byte], offset: Int, length: Int) = System.arraycopy(data, offset, bytes, 0, length)

  def toBinary = bytes.map { x => String.format("%8s", (x & 0xFF).toBinaryString).replace(' ', '0') }.mkString(" ")
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
    (0 to vectorSize).map { distance => 1.0f - (distance.toFloat / half) }.toArray
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
