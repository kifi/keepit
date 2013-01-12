package com.keepit.search

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel.MapMode
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.nio.MappedByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.Random

object ProbablisticLRU {
  def apply(file: File, tableSize: Int, numHashFuncs: Int, syncEvery: Int) = {
    val bufferSize = tableSize * 4 + 4
    val isNew = file.exists
    val raf = new RandomAccessFile(file, "rw")
    val byteBuffer = raf.getChannel().map(MapMode.READ_WRITE, 0, bufferSize)
    if (isNew) {
      byteBuffer.putInt(0, tableSize)
      byteBuffer.force()
    } else {
      if (tableSize != byteBuffer.getInt(0)) throw new ProbablisticLRUException("table size mismatch")
    }
    new ProbablisticLRU(byteBuffer, tableSize, numHashFuncs, syncEvery)
  }

  def apply(tableSize: Int, numHashFuncs: Int, syncEvery: Int) = {
    val bufferSize = tableSize * 4 + 4
    val byteBuffer = ByteBuffer.allocate(bufferSize)
    byteBuffer.putInt(0, tableSize)
    new ProbablisticLRU(byteBuffer, tableSize, numHashFuncs, syncEvery)
  }

  def valueHash(key: Long, value: Long): Int = {
    // positive integer, excluding zero. negative int (specifically -1) and zero are special
    (((key ^ value) & 0x7FFFFFFFFFFFFFFFL) % 0x7FFFFFFFL).toInt + 1
  }

  class Likeliness(key: Long, candidates: Map[Int, Int]) {
    def apply(value: Long) = {
      val hash = valueHash(key, value)
      candidates.get(hash) match {
        case Some(count) => 1.0f/(count.toFloat)
        case None => 0.0f
      }
    }

    def count(value: Long) = {
      val hash = valueHash(key, value)
      candidates.get(hash) match {
        case Some(count) => count
        case None => 0
      }
    }
  }
}

class ProbablisticLRU(byteBuffer: ByteBuffer, tableSize: Int, numHashFuncs: Int, syncEvery: Int) {
  import ProbablisticLRU._

  private[this] val intBuffer = byteBuffer.asIntBuffer
  private[this] val rnd = new Random

  private[this] var inserts = new AtomicLong(0L)
  private[this] var syncs = 0L

  def put(key: Long, value: Long) {
    decay
    putValueHash(key, valueHash(key, value))
    val ins = inserts.incrementAndGet()
    if ((ins % syncEvery) == 0) sync
  }

  def get(key: Long): ProbablisticLRU.Likeliness = new ProbablisticLRU.Likeliness(key, getValueHashes(key))

  def get(key: Long, values: Seq[Long]): Map[Long, Int] = {
    val likeliness = get(key)
    values.foldLeft(Map.empty[Long, Int]){ (m, value) =>
      val c = likeliness.count(value)
      if (c > 0)  m + (value -> c) else m
    }
  }

  def decay = {
    // randomly clear positions
    var i = 0
    while (i < numHashFuncs) {
      val pos = rnd.nextInt(numHashFuncs) + 1
      intBuffer.put(pos, 0)
      i += 1
    }
    this
  }

  def sync = {
    byteBuffer match {
      case mappedByteBuffer: MappedByteBuffer => mappedByteBuffer.force()
      case _ =>
    }
    syncs += 1
    this
  }

  def numInserts = inserts.get
  def numSyncs = syncs

  private[this] def putValueHash(key: Long, hash: Int) {
    var pset = Set.empty[Int]
    var filled = 0
    foreachPosition(key){ pos =>
      if (intBuffer.get(pos) == 0) {
        // always fill empty positions
        intBuffer.put(pos, if (filled < numHashFuncs/2) hash else -1)
        filled += 1
      }
      else pset += pos
    }
    if (filled < numHashFuncs) {
      // randomly overwrite the half of the non-empty positions
      val parray = pset.toArray
      var i = 0
      while (i < (parray.length - numHashFuncs/2)) {
        val index = rnd.nextInt(parray.length - i) + i
        val pos = parray(index)
        parray(index) = parray(i)
        intBuffer.put(pos, hash)
        i += 1
      }
    }
  }

  private def getValueHashes(key: Long): Map[Int, Int] = {
    var ret = Map.empty[Int, Int]
    foreachPosition(key){ pos =>
      val value = intBuffer.get(pos)
      ret += (value -> (ret.getOrElse(value, 0) + 1))
    }
    ret
  }

  private[this] def foreachPosition(key: Long)(f: Int => Unit) {
    var v = key & 0x7FFFFFFFFFFFFFFFL
    var i = 0
    val tsize = tableSize.toLong
    while (i < numHashFuncs) {
      v = (v * 0x5DEECE66DL + 0x123456789L) & 0x7FFFFFFFFFFFFFFFL // linear congruential generator
      f((v % tsize).toInt + 1)
      i += 1
    }
  }
}

class ProbablisticLRUException(msg: String) extends Exception(msg)
