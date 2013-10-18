package com.keepit.common.cache

import com.keepit.search.{FullFilterChunkId}

import scala.concurrent.duration._

import com.keepit.serializer.BinaryFormat
import com.keepit.common.logging.AccessLog

import java.nio.{IntBuffer, ByteBuffer}


object IntArrayBinarySerializer extends BinaryFormat[Array[Int]] {

  protected def reads(data: Array[Byte], offset: Int, length: Int): Array[Int] = {
    val intBuffer = ByteBuffer.wrap(data, offset, length).asIntBuffer
    val outArray = new Array[Int]((data.length - 1)/4)
    intBuffer.get(outArray)
    outArray
  }

  protected def writes(value: Array[Int]): Array[Byte] = {
    val byteBuffer = ByteBuffer.allocate(1 + value.size*4)
    byteBuffer.put(1.toByte) // we have something
    byteBuffer.asIntBuffer.put(value)
    byteBuffer.array
  }

}


case class ProbablisticLRUChunkKey(id: FullFilterChunkId) extends Key[Array[Int]] {
  override val version = 1
  val namespace = "flower_filter"
  def toKey(): String = id.name + "/chunk_" + id.chunk.toString
}

class ProbablisticLRUChunkCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[ProbablisticLRUChunkKey, Array[Int]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)(IntArrayBinarySerializer)
