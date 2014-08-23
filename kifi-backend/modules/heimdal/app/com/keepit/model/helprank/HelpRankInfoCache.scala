package com.keepit.model.helprank

import com.keepit.common.cache.{ PrimitiveCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.model.{ NormalizedURI }

import scala.concurrent.duration.Duration

case class UriDiscoveryCountKey(uriId: Id[NormalizedURI]) extends Key[Int] {
  override val version = 1
  val namespace = "uri_discovery_count"
  def toKey(): String = uriId.id.toString
}

class UriDiscoveryCountCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[UriDiscoveryCountKey, Int](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class UriReKeepCountKey(uriId: Id[NormalizedURI]) extends Key[Int] {
  override val version = 1
  val namespace = "uri_rekeep_count"
  def toKey(): String = uriId.id.toString
}

class UriReKeepCountCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[UriReKeepCountKey, Int](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
