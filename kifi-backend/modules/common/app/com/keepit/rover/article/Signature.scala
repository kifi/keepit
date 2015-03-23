package com.keepit.rover.article

import java.util.Arrays
import javax.xml.bind.DatatypeConverter._
import org.apache.lucene.analysis.standard.StandardTokenizer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import java.io.StringReader

import scala.math._

class Signature(val bytes: Array[Byte]) {

  def similarTo(other: Signature): Double = Signature.similarity(this.bytes, other.bytes)

  def toBase64() = printBase64Binary(bytes)

  override def equals(other: Any) = {
    other match {
      case other: Signature => Arrays.equals(bytes, other.bytes)
      case _ => false
    }
  }
  override def toString() = classOf[Signature].getName() + bytes.mkString("(", ",", ")")
}

object Signature {

  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  implicit val format: Format[Signature] = Format[Signature](Reads.of[String].fmap(Signature.apply), Writes { signature => JsString(signature.toBase64()) })

  val SIZE = 100
  def apply(arr: Array[Byte]) = new Signature(arr)
  def apply(base64: String) = new Signature(parseBase64Binary(base64))

  def empty(): Signature = {
    val bytes = new Array[Byte](Signature.SIZE)
    Arrays.fill(bytes, Int.MaxValue.toByte)
    Signature(bytes)
  }

  def similarity(arr1: Array[Byte], arr2: Array[Byte]): Double = {
    if (arr1.length == arr2.length) {
      arr1.zip(arr2).count { pair => pair._1 == pair._2 }.toDouble / arr1.length.toDouble
    } else {
      0.0d
    }
  }

  abstract class Builder(windowSize: Int) {

    private[this] val sketch = new Array[Int](Signature.SIZE)
    Arrays.fill(sketch, Int.MaxValue)

    private[this] val window = new Array[Int](windowSize + 1)

    private[this] val cancelerShift = windowSize % 32

    private[this] var ptr = windowSize
    private[this] var canceler = 0

    def add(text: String): Builder = {
      var h = window(ptr % windowSize)
      tokenize(text) { (termBuf: Array[Char], termLen: Int) =>
        h = ((h >>> 31) | (h << 1)) ^ hash(termBuf, termLen)
        ptr += 1
        canceler = window(ptr % windowSize)
        window(ptr % windowSize) = h
        updateSketch(h ^ ((canceler << cancelerShift) | (canceler >>> (32 - cancelerShift))))
      }
      this
    }

    protected def tokenize(text: String)(addTerm: (Array[Char], Int) => Unit): Unit

    def add(fields: Seq[String]): Builder = {
      fields.foreach(add)
      this
    }

    def build() = {
      // borrowing the idea from b-bit minwise hash. we take the lowest 8 bits to save space.
      Signature(sketch.map { _.toByte }.toArray)
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
      while (i < Signature.SIZE) {
        v = (v * 0x5DEECE66DL + 0x123456789L) & 0x7FFFFFFFFFFFFFFFL // linear congruential generator
        sketch(i) = min(sketch(i), (v % 0x7FFFFFFFL).toInt) // positive int
        i += 1
      }
    }
  }
}

class SignatureBuilder(windowSize: Int = 20) extends Signature.Builder(windowSize) {

  override protected def tokenize(text: String)(addTerm: (Array[Char], Int) => Unit): Unit = {
    val ts = new StandardTokenizer(new StringReader(text))
    val termAttr = ts.getAttribute(classOf[CharTermAttribute])

    try {
      ts.reset()
      while (ts.incrementToken()) addTerm(termAttr.buffer(), termAttr.length())
      ts.end()
    } finally {
      ts.close()
    }
  }

}