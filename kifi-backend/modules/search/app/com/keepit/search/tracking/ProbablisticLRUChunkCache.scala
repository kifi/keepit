package com.keepit.search.tracking

import com.keepit.common.logging.AccessLog
import com.keepit.serializer.ArrayBinaryFormat
import scala.concurrent.duration._
import com.keepit.common.cache.BinaryCacheImpl
import com.keepit.common.cache.CacheStatistics
import com.keepit.common.cache.FortyTwoCachePlugin
import com.keepit.common.cache.Key

case class ProbablisticLRUChunkKey(id: FullFilterChunkId) extends Key[Array[Int]] {
  override val version = 1
  val namespace = "flower_filter"
  def toKey(): String = id.name + "/chunk_" + id.chunk.toString
}

class ProbablisticLRUChunkCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[ProbablisticLRUChunkKey, Array[Int]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(ArrayBinaryFormat.intArrayFormat)
