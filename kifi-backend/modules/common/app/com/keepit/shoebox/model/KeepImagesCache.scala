package com.keepit.shoebox.model

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.model.{ Keep }
import com.keepit.rover.model.BasicImages

import scala.concurrent.duration.Duration

case class KeepImagesKey(keepId: Id[Keep]) extends Key[BasicImages] {
  override val version = 1
  val namespace = "keep_images_by_keep_id"
  def toKey(): String = keepId.id.toString
}

class KeepImagesCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[KeepImagesKey, BasicImages](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
