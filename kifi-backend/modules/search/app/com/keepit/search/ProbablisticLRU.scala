package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.common.cache.{ProbablisticLRUChunkCache, ProbablisticLRUChunkKey}

import scala.math._
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel.MapMode
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.nio.MappedByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.Random

import com.google.inject.Inject
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.sns.model.NotFoundException



case class ProbablisticLRUName(name: String)


trait MultiChunkBuffer {
  def getChunk(key: Long) : IntBufferWrapper
  def chunkSize : Int
}


trait IntBufferWrapper {
  def get(pos: Int): Int
  def put(pos: Int, value: Int): Unit
  def sync() : Unit
}

trait SimpleLocalBuffer extends MultiChunkBuffer {

  protected val byteBuffer: ByteBuffer

  private[this] def intBuffer = byteBuffer.asIntBuffer

  def chunkSize : Int = byteBuffer.getInt(0)

  def getChunk(key: Long) : IntBufferWrapper = new IntBufferWrapper {

    def get(pos: Int) : Int = intBuffer.get(pos)

    def put(pos: Int, value: Int) : Unit = synchronized { 
      intBuffer.put(pos, value)
    }

    def sync : Unit = synchronized { 
      byteBuffer match {
        case mappedByteBuffer: MappedByteBuffer => mappedByteBuffer.force()
        case _ =>
      }
    }

  }
  
}


class FileResultClickTrackerBuffer(file: File, tableSize: Int) extends SimpleLocalBuffer {

  protected val byteBuffer : ByteBuffer = {
    val bufferSize = tableSize * 4 + 4
    val isNew = !file.exists
    val raf = new RandomAccessFile(file, "rw")
    val byteBuffer = raf.getChannel().map(MapMode.READ_WRITE, 0, bufferSize)
    if (isNew) {
      byteBuffer.putInt(0, tableSize)
      byteBuffer.force()
    } else {
      if (tableSize != byteBuffer.getInt(0)) throw new ProbablisticLRUException("table size mismatch")
    }
    byteBuffer
  }

}


class InMemoryResultClickTrackerBuffer(tableSize: Int) extends SimpleLocalBuffer {
  
  protected val byteBuffer : ByteBuffer = {
    val bufferSize = tableSize * 4 + 4
    val _byteBuffer = ByteBuffer.allocate(bufferSize)
    _byteBuffer.putInt(0, tableSize)
    _byteBuffer
  }

}


class S3BackedBuffer(cache: ProbablisticLRUChunkCache, dataStore : ProbablisticLRUStore, val filterName: ProbablisticLRUName) extends MultiChunkBuffer {


  private def loadChunk(chunkId: Int) : Array[Int] = {
    val fullId = FullFilterChunkId(filterName.name, chunkId)
    val key = ProbablisticLRUChunkKey(fullId)
    cache.getOrElseOpt(key)(dataStore.get(fullId)) match {
      case Some(intArray) => intArray
      case None =>
        val intBuffer = new Array[Int](chunkSize+1)
        intBuffer(0)=chunkSize
        intBuffer
    }
  }

  private def saveChunk(chunkId: Int, chunk: Array[Int]) : Unit = { 
    val fullId = FullFilterChunkId(filterName.name, chunkId)
    dataStore += (fullId, chunk)
    cache.remove(ProbablisticLRUChunkKey(fullId))
  }

  private def numChunks : Int = 4000 

  def chunkSize : Int = 4000

  def getChunk(key: Long) = {
    val chunkId = ((key % chunkSize*numChunks) / chunkSize).toInt
    val thisChunk : Array[Int] = loadChunk(chunkId)
    var dirtyEntries : Set[Int] = Set[Int]()
    
    new IntBufferWrapper {
      
      def get(pos: Int) : Int = thisChunk(pos)

      def sync : Unit = synchronized {
        val storedChunk = loadChunk(chunkId)
        dirtyEntries.foreach { pos =>
          storedChunk(pos) = thisChunk(pos)
        }
        saveChunk(chunkId, storedChunk)
      }

      def put(pos: Int, value: Int) = synchronized {
        thisChunk(pos) = value
        dirtyEntries = dirtyEntries + pos
      }

    }
  }

}



class S3BackedResultClickTrackerBuffer @Inject() (cache: ProbablisticLRUChunkCache, dataStore : ProbablisticLRUStore) extends S3BackedBuffer(cache, dataStore, ProbablisticLRUName("ResultClickTracker"))



class MultiplexingBuffer(buf: MultiChunkBuffer, bufs: MultiChunkBuffer*) extends MultiChunkBuffer {

  def chunkSize : Int = buf.chunkSize

  def getChunk(key: Long) : IntBufferWrapper = {
    val chunks = buf.getChunk(key)::bufs.map(_.getChunk(key)).toList
    new IntBufferWrapper {
      
      def get(pos: Int) = chunks.head.get(pos)

      def sync : Unit = synchronized {
        chunks.foreach(_.sync)
      }
  
      def put(pos: Int, value: Int) : Unit = synchronized { 
        chunks.foreach(_.put(pos, value)) 
      }
    
    }
  }

}



class ProbablisticLRU(mcBuffer: MultiChunkBuffer, numHashFuncs : Int, syncEvery : Int) {
  
  class Likeliness(key: Long, positions: Array[Int], values: Array[Int], norm: Float) {
    def apply(value: Long) = {
      var count = 0.0f
      var i = 0
      while (i < positions.length) {
        val vhash = valueHash(value, positions(i))
        if (values(i) == vhash) count += 1.0f
        i += 1
      }
      count/norm
    }

    def count(value: Long) = {
      var count = 0
      var i = 0
      while (i < positions.length) {
        val vhash = valueHash(value, positions(i))
        if (values(i) == vhash) count += 1
        i += 1
      }
      count
    }
  }

  def valueHash(value: Long, position: Int): Int = {
    // 32-bit integer, excluding zero. zero is special
    (((value * position.toLong) ^ value) % 0xFFFFFFFFL + 1L).toInt
  }

  private[this] val tableSize = mcBuffer.chunkSize

  private[this] val rnd = new Random

  private[this] var inserts = new AtomicLong(0L)
  private[this] var syncs = 0L
  private[this] var dirtyChunks = Set[IntBufferWrapper]()

  def setSeed(seed: Long) = rnd.setSeed(seed)

  def put(key: Long, value: Long, updateStrength: Double = 0.5d) {
    putValueHash(key, value, updateStrength)
    val ins = inserts.incrementAndGet()
    if ((ins % syncEvery) == 0) sync
  }

  def get(key: Long) = {
    val (p, h) = getValueHashes(key)
    new Likeliness(key, p, h, numHashFuncs.toFloat)
  }

  def get(key: Long, values: Seq[Long]): Map[Long, Int] = {
    val likeliness = get(key)
    values.foldLeft(Map.empty[Long, Int]){ (m, value) =>
      val c = likeliness.count(value)
      if (c > 0)  m + (value -> c) else m
    }
  }

  def sync = synchronized {
    dirtyChunks.map(_.sync)
    dirtyChunks = Set[IntBufferWrapper]()
    syncs += 1
    this
  }

  def numInserts = inserts.get
  def numSyncs = syncs

  private[this] def putValueHash(key: Long, value: Long, updateStrength: Double) {
    val bufferChunk = mcBuffer.getChunk(key)
    var positions = new Array[Int](numHashFuncs)
    var i = 0
    foreachPosition(key){ pos =>
      positions(i) = pos
      i += 1
    }
    // randomly overwrite the positions proportionally to updateStrength
    i = 0
    val numUpdatePositions = min(ceil(numHashFuncs.toDouble * updateStrength).toInt, numHashFuncs)
    while (i < numUpdatePositions) {
      val index = rnd.nextInt(positions.length - i) + i
      val pos = positions(index)
      positions(index) = positions(i)
      bufferChunk.put(pos, valueHash(value, pos))
      i += 1
    }
  }

  private def getValueHashes(key: Long): (Array[Int], Array[Int]) = {
    val bufferChunk = mcBuffer.getChunk(key)
    dirtyChunks = dirtyChunks + bufferChunk
    var p = new Array[Int](numHashFuncs)
    var h = new Array[Int](numHashFuncs)
    var i = 0
    foreachPosition(key){ pos =>
      p(i) = pos
      h(i) = bufferChunk.get(pos)
      i += 1
    }
    (p, h)
  }

  private[this] def foreachPosition(key: Long)(f: Int => Unit) {
    var v = key & 0x7FFFFFFFFFFFFFFFL
    var i = 0
    val tsize = tableSize.toLong
    while (i < numHashFuncs) {
      v = (v * 0x5DEECE66DL + 0x123456789L) & 0x7FFFFFFFFFFFFFFFL // linear congruential generator
      // pass the position to the given function
      f((v % tsize).toInt + 1)
      i += 1
    }
  }
}


class ProbablisticLRUException(msg: String) extends Exception(msg)
