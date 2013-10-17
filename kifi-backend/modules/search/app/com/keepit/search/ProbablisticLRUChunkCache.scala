package com.keepit.common.cache

import com.keepit.common.logging.AccessLog
import com.keepit.search.{FullFilterChunkId}
import com.keepit.serializer.ArrayBinarySerializer
import scala.concurrent.duration._

case class ProbablisticLRUChunkKey(id: FullFilterChunkId) extends Key[Array[Int]] {
  override val version = 1
  val namespace = "flower_filter"
  def toKey(): String = id.name + "/chunk_" + id.chunk.toString
}

class ProbablisticLRUChunkCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[ProbablisticLRUChunkKey, Array[Int]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)(ArrayBinarySerializer.intArraySerializer)
