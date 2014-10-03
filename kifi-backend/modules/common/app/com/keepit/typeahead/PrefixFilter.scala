package com.keepit.typeahead

import com.keepit.common.db.Id
import scala.collection.mutable.ArrayBuffer
import scala.math.min
import java.text.Normalizer
import java.nio.ByteBuffer
import com.keepit.serializer.{ BinaryFormat, ArrayBinaryFormat }
import play.api.libs.json._

object PrefixFilter {

  private[typeahead] def eval[T](filter: PrefixFilter[T], query: Array[String]): ArrayBuffer[Id[T]] = {
    val in = filter.data

    in(0) match {
      case 1 => evalV1[T](in, query)
      case version => throw new Exception(s"unknown version: $version")
    }
  }

  private[this] def evalV1[T](in: Array[Long], query: Array[String]): ArrayBuffer[Id[T]] = {
    val result = new ArrayBuffer[Id[T]]
    var probes = query.map(genFilter).toArray
    var idx = 1
    while (idx < in.length) {
      var id = in(idx)
      val filter = in(idx + 1)
      if (probes.forall { probe => probe == (probe & filter) }) result += Id[T](id)
      idx += 2
    }
    result
  }

  private[this] val diacriticalMarksRegex = "\\p{InCombiningDiacriticalMarks}+".r
  final val tokenBoundary = "\\s+"

  @inline final def normalize(str: String) = diacriticalMarksRegex.replaceAllIn(Normalizer.normalize(str.trim, Normalizer.Form.NFD), "").toLowerCase
  @inline final def tokenize(str: String) = tokenizeNormalizedName(normalize(str))
  @inline final def tokenizeNormalizedName(str: String) = str.split(tokenBoundary).filter(_.length > 0)

  private[this] val numHashFuncs = Array(8, 4, 2, 1) // asymmetric bloom filter
  @inline private[this] def next(v: Int): Int = (v * 1103515245 + 12345) // linear congruential generator

  private[typeahead] def genFilter(token: String): Long = {
    var filter = 0L
    val maxPrefixLen = min(numHashFuncs.length, token.length)
    var i = 0
    while (i < maxPrefixLen) {
      val prefix = token.subSequence(0, i + 1)
      var hash = prefix.hashCode
      var j = numHashFuncs(i)
      while (j > 0) {
        hash = next(hash)
        filter = filter | (1 << ((hash >> 25) & 0x3F))
        j -= 1
      }
      i += 1
    }
    filter
  }

  def toByteArray[T](filter: PrefixFilter[T]): Array[Byte] = {
    val data = filter.data
    val byteBuffer = ByteBuffer.allocate(data.length * 8)
    byteBuffer.asLongBuffer.put(data)
    byteBuffer.array
  }

  def fromByteArray[T](bytes: Array[Byte]): PrefixFilter[T] = {
    require(bytes.length % 8 == 0, "Invalid byte array: cannot be converted to PrefixFilter")
    val intBuffer = ByteBuffer.wrap(bytes).asLongBuffer
    val outArray = new Array[Long](bytes.length / 8)
    intBuffer.get(outArray)
    new PrefixFilter(outArray)
  }

  implicit def binaryFormat[T]: BinaryFormat[PrefixFilter[T]] = ArrayBinaryFormat.longArrayFormat.map(_.data, new PrefixFilter(_))

  implicit def format[T] = new Format[PrefixFilter[T]] {
    def reads(json: JsValue) = json.validate[Seq[Long]].map(data => new PrefixFilter(data.toArray))
    def writes(filter: PrefixFilter[T]) = JsArray(filter.data.map(JsNumber(_)))
  }
}

class PrefixFilter[T](val data: Array[Long]) {
  def filterBy(query: Array[String]): Seq[Id[T]] = PrefixFilter.eval[T](this, query)
  def isEmpty = (data.length == 1)
  override def toString = s"[PrefixFilter] (len=${data.length - 1}) ${data.drop(1).take(10).grouped(2).map { t => s"${t(0)} -> ${java.lang.Long.toHexString(t(1))}" }.mkString(",")}"
}

class PrefixFilterBuilder[T] {
  private[this] val out = new ArrayBuffer[Long]

  out += 1L // version

  def add(id: Id[T], name: String) = { // expects two tokens (first/last names) usually, three tokens may be ok, four tokens will significantly compromise filtering precision
    val filter = PrefixFilter.tokenize(name).foldLeft(0L) { (filter, token) => (filter | PrefixFilter.genFilter(token)) }
    out += id.id
    out += filter
  }

  def build(): PrefixFilter[T] = {
    new PrefixFilter(out.toArray)
  }
}
