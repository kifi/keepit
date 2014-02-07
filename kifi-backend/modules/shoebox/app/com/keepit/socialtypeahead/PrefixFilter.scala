package com.keepit.socialtypeahead

import com.keepit.common.db.Id
import com.keepit.common.strings._
import scala.collection.mutable.ArrayBuffer
import scala.math.min
import java.io._
import java.text.Normalizer

object PrefixFilter {

  private[socialtypeahead] def eval[T](filter: PrefixFilter[T], names: Seq[String]): ArrayBuffer[Id[T]] = {
    val in = filter.data

    in(0) match {
      case 1 => evalV1[T](in, names)
      case version => throw new Exception(s"unknown version: $version")
    }
  }

  private[this] def evalV1[T](in: Array[Long], names: Seq[String]): ArrayBuffer[Id[T]] = {
    val result = new ArrayBuffer[Id[T]]
    var probes = names.map(genFilter).toArray
    var idx = 1
    while (idx < in.length) {
      var id = in(idx)
      val filter = in(idx + 1)
      if (probes.forall{ probe => probe == (probe & filter) }) result += Id[T](id)
      idx += 2
    }
    result
  }

  @inline def normalize(str: String) = Normalizer.normalize(str.trim, Normalizer.Form.NFD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "").toLowerCase

  private[this] val numHashFuncs = Array(8, 4, 2, 1) // asymmetric bloom filter
  @inline private[this] def init(k: Long): Long = k & 0x7FFFFFFFFFFFFFFFL
  @inline private[this] def next(v: Long): Long = (v * 0x5DEECE66DL + 0x123456789L) & 0x7FFFFFFFFFFFFFFFL // linear congruential generator

  private[socialtypeahead] def genFilter(inputName: String): Long = {
    val name = normalize(inputName)
    var filter = 0L
    val maxPrefixLen = min(numHashFuncs.length, name.length)
    var i = 0
    while (i < maxPrefixLen) {
      val prefix = name.subSequence(0, i + 1)
      var hash = init(prefix.hashCode.toLong)
      var j = numHashFuncs(i)
      while (j > 0) {
        hash = next(hash)
        filter = filter | (1 << (hash % 64))
      }
    }
    filter
  }
}

class PrefixFilter[T](val data: Array[Long]) extends AnyVal {
  def filterBy(names: Seq[String]): Seq[Id[T]] = PrefixFilter.eval[T](this, names)
}

class PrefixFilterBuilder[T] {
  private[this] val out = new ArrayBuffer[Long]

  out += 1L // version

  def add(id: Id[T], names: Seq[String]) = { // expects two names usually, three names may be ok, four names will significantly compromise filtering precision
    val filter = names.foldLeft(0L){ (filter, name) => (filter | PrefixFilter.genFilter(name)) }
    out += id.id
    out += filter
  }

  def build(): PrefixFilter[T] = {
    new PrefixFilter(out.toArray)
  }
}
