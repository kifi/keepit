package com.keepit.common.cache

import com.keepit.search.{FullFilterChunkId}

import scala.concurrent.duration._

import com.keepit.serializer.{BinaryFormat}

import java.nio.{IntBuffer, ByteBuffer}


object IntArrayBinarySerializer extends BinaryFormat[Array[Int]] {
  
  def reads(data: Array[Byte]): Array[Int] = {
    val intBuffer = ByteBuffer.wrap(data).asIntBuffer
    val outArray = new Array[Int](data.length/4)
    intBuffer.get(outArray)
    outArray
  }
  
  def writes(value: Array[Int]): Array[Byte] = {
    val byteBuffer = ByteBuffer.allocate(value.size*4)
    byteBuffer.asIntBuffer.put(value.array)
    byteBuffer.array
  }

}


case class ProbablisticLRUChunkKey(id: FullFilterChunkId) extends Key[Array[Int]] {
  override val version = 1
  val namespace = "flower_filter"
  def toKey(): String = id.name + "/chunk_" + id.chunk.toString 
}

class ProbablisticLRUChunkCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[ProbablisticLRUChunkKey, Array[Int]](innermostPluginSettings, innerToOuterPluginSettings:_*)(IntArrayBinarySerializer)