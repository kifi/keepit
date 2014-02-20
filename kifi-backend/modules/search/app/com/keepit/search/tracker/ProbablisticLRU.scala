package com.keepit.search.tracker

import com.keepit.common.logging.Logging
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.service.RequestConsolidator
import scala.math._
import scala.concurrent._
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel.MapMode
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.Random
import com.google.inject.Inject
import com.keepit.search.ProbablisticLRUStore
import com.keepit.search.FullFilterChunkId
import com.keepit.common.akka.SafeFuture

case class ProbablisticLRUName(name: String)

trait MultiChunkBuffer {
  def getChunk(key: Long) : IntBufferWrapper
  def getChunkFuture(key: Long) : Future[IntBufferWrapper] = Future.successful(getChunk(key))
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


class S3BackedBuffer(cache: ProbablisticLRUChunkCache, dataStore : ProbablisticLRUStore, val filterName: ProbablisticLRUName) extends MultiChunkBuffer with Logging {

  private[this] val consolidateChunkReq = new RequestConsolidator[Int, Array[Int]](1 second)

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

  private def saveChunk(chunkId: Int, chunk: Array[Int]) : ProbablisticLRUChunkKey  = {
    val fullId = FullFilterChunkId(filterName.name, chunkId)
    dataStore += (fullId, chunk)
    val cacheKey = ProbablisticLRUChunkKey(fullId)
    cache.set(cacheKey, chunk)
    cacheKey
  }

  private def numChunks : Int = 4000

  def chunkSize : Int = 4000

  def warmCache() : Unit = (0 to numChunks).foreach{ chunkId =>
    val chunk = loadChunk(chunkId)
    val cacheKey = saveChunk(chunkId, chunk).toString
    log.info(s"Warmed cache for $cacheKey.")
  }

  override def getChunk(key: Long): IntBufferWrapper = {
    Await.result(getChunkFuture(key), 10 seconds)
  }

  override def getChunkFuture(key: Long) : Future[IntBufferWrapper] = {
    val chunkId = ((Math.abs(key) % chunkSize*numChunks) / chunkSize).toInt
    val future  = consolidateChunkReq(chunkId){ cid => SafeFuture{ loadChunk(cid) } }
    future.map{ thisChunk =>
      var dirtyEntries : Set[Int] = Set[Int]()

      new IntBufferWrapper {

        val syncLock : AnyRef = new AnyRef
        val putLock  : AnyRef = new AnyRef

        def get(pos: Int) : Int = thisChunk(pos)

        def sync : Unit = SafeFuture {
          syncLock.synchronized {
            val storedChunk = loadChunk(chunkId)
            dirtyEntries.foreach { pos => storedChunk(pos) = thisChunk(pos) }
            dirtyEntries = Set()
            consolidateChunkReq.set(chunkId, Future.successful(storedChunk))
            saveChunk(chunkId, storedChunk)
          }
        }

        def put(pos: Int, value: Int) = putLock.synchronized {
          thisChunk(pos) = value
          dirtyEntries = dirtyEntries + pos
        }
      }
    }
  }

}



class S3BackedResultClickTrackerBuffer @Inject() (cache: ProbablisticLRUChunkCache, dataStore : ProbablisticLRUStore) extends S3BackedBuffer(cache, dataStore, ProbablisticLRUName("ResultClickTracker"))




class ProbablisticLRU(buffer: MultiChunkBuffer, val numHashFuncs : Int, syncEvery : Int) {

  class Probe(key: Long, positions: Array[Int], values: Array[Int]) {
    def count(value: Long) = {
      var count = 0
      var i = 0
      while (i < positions.length) {
        if (values(i) == valueHash(value, positions(i))) count += 1
        i += 1
      }
      count
    }
  }

  @inline private[this] def valueHash(value: Long, position: Int): Int = {
    // 32-bit integer, excluding zero. zero is special
    (((value * position.toLong) ^ value) % 0xFFFFFFFFL + 1L).toInt
  }


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

  def get(key: Long): Probe = {
    Await.result(getFuture(key), 10 seconds)
  }

  def getFuture(key: Long): Future[Probe] = {
    getValueHashesFuture(key).map{ case (p, h) => new Probe(key, p, h) }
  }

  def get(key: Long, values: Seq[Long]): Map[Long, Int] = {
    val probe = get(key)
    values.foldLeft(Map.empty[Long, Int]){ (m, value) =>
      val c = probe.count(value)
      if (c > 0)  m + (value -> c) else m
    }
  }

  def sync = synchronized {
    dirtyChunks.foreach(_.sync)
    dirtyChunks = Set[IntBufferWrapper]()

    syncs += 1
    this
  }

  def numInserts = inserts.get
  def numSyncs = syncs

  protected def putValueHash(key: Long, value: Long, updateStrength: Double) {
    def putValueHashOnce(bufferChunk: IntBufferWrapper): Boolean = {
      val (positions, values) = Await.result(getValueHashesFuture(key), 10 seconds)

      // count open positions
      var openCount = 0
      var i = 0
      while (i < positions.length) {
        if (values(i) != valueHash(value, positions(i))) {
          positions(openCount) = positions(i)
          openCount += 1
        }
        i += 1
      }

      // randomly overwrite the positions proportionally to updateStrength
      i = 0
      val numUpdatePositions = min(ceil(openCount * updateStrength).toInt, numHashFuncs)
      while (i < numUpdatePositions && i < openCount) {
        val index = rnd.nextInt(openCount - i) + i
        val pos = positions(index)
        positions(index) = positions(i)
        bufferChunk.put(pos, valueHash(value, pos))
        i += 1
      }
      (i > 0)
    }

    val bufferChunk = buffer.getChunk(key)
    if (putValueHashOnce(bufferChunk)) dirtyChunks = dirtyChunks + bufferChunk
  }

  protected def getValueHashes(key: Long): (Array[Int], Array[Int]) = {
    Await.result(getValueHashesFuture(key), 10 seconds)
  }

  protected def getValueHashesFuture(key: Long): Future[(Array[Int], Array[Int])] = {
    buffer.getChunkFuture(key).map{ bufferChunk =>
      val tableSize = buffer.chunkSize
      val p = new Array[Int](numHashFuncs)
      val h = new Array[Int](numHashFuncs)

      var v = init(key)
      var i = 0
      val tsize = tableSize.toLong
      while (i < numHashFuncs) {
        v = next(v)
        val pos = (v % tsize).toInt + 1
        p(i) = pos
        h(i) = bufferChunk.get(pos)
        i += 1
      }
      (p, h)
    }
  }

  @inline private[this] def init(k: Long) = k & 0x7FFFFFFFFFFFFFFFL
  @inline private[this] def next(v: Long) = (v * 0x5DEECE66DL + 0x123456789L) & 0x7FFFFFFFFFFFFFFFL // linear congruential generator
}


class ProbablisticLRUException(msg: String) extends Exception(msg)
