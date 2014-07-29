package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.common.cache.{ StringCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration.Duration

/**
 * placeholder to all user related caches that we would like to keep in the shoebox scope
 */
case class UserImageUrlCacheKey(userId: Id[User], width: Int, imageName: String) extends Key[String] {
  override val version = 1
  val namespace = "user_image_by_width"
  def toKey(): String = s"$userId#$width#$imageName"
}

class UserImageUrlCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends StringCacheImpl[UserImageUrlCacheKey](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

