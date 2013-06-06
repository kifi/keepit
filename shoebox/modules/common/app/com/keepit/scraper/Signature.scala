package com.keepit.scraper

import org.apache.lucene.analysis.standard.StandardTokenizer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.store.InputStreamDataInput
import org.apache.lucene.store.OutputStreamDataOutput
import org.apache.lucene.util.Version
import java.io.StringReader
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.util.Arrays
import javax.xml.bind.DatatypeConverter._
import scala.math._

object Signature {
  def apply(arr: Array[Byte]) = new Signature(arr)
  def apply(base64: String) = new Signature(parseBase64Binary(base64))
}

class Signature(val bytes: Array[Byte]) {

  def similarTo(other: Signature): Double = {
    if (bytes.length == other.bytes.length) {
      bytes.zip(other.bytes).filter{ pair => pair._1 == pair._2 }.size.toDouble / 100.0d
    } else {
      0.0d
    }
  }

  def toBase64() = printBase64Binary(bytes)

  override def equals(other: Any) = {
    other match {
      case other: Signature => Arrays.equals(bytes, other.bytes)
      case _ => false
    }
  }
  override def toString() = classOf[Signature].getName() + bytes.mkString("(", ",", ")")
}

class SignatureBuilder(windowSize: Int = 20) {

  private[this] val sketch = new Array[Int](100)
  Arrays.fill(sketch, Int.MaxValue)

  private[this] val window = new Array[Int](windowSize + 1)
  Arrays.fill(sketch, Int.MaxValue)

  private[this] val cancelerShift = windowSize % 32

  private[this] var ptr = windowSize
  private[this] var canceler = 0

  def add(text: String) = {
    val ts = new StandardTokenizer(Version.LUCENE_41, new StringReader(text))
    val termAttr = ts.getAttribute(classOf[CharTermAttribute])
    var h = window(ptr % windowSize)

    ts.reset()
    while (ts.incrementToken()) {
      h = ((h >>> 31) | (h << 1)) ^ hash(termAttr.buffer(), termAttr.length())
      ptr += 1
      canceler = window(ptr % windowSize)
      window(ptr % windowSize) = h
      updateSketch(h ^ ((canceler << cancelerShift)|(canceler >>> (32 - cancelerShift))))
      ptr
    }
    this
  }

  def build() = {
     // borrowing the idea from b-bit minwise hash. we take the lowest 8 bits to save space.
    Signature(sketch.map{ _.toByte }.toArray)
  }

  private def hash(arr: Array[Char], len: Int) = {
    // Adler-32
    var a = 0
    var b = 0
    var i = 0
    while (i < len) {
      val c = arr(i)
      a += c
      b += b + c
      i += 1
    }
    ((a % 65521) + (b % 65521) * 65536)
  }

  private def updateSketch(seed: Int) {
    var v = seed.toLong & 0x7FFFFFFFFFFFFFFFL
    var i = 0
    val sketchSize = sketch.length
    while (i < 100) {
      v = (v * 0x5DEECE66DL + 0x123456789L) & 0x7FFFFFFFFFFFFFFFL // linear congruential generator
      sketch(i) = min(sketch(i), (v % 0x7FFFFFFFL).toInt) // positive int
      i += 1
    }
  }
}
