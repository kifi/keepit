package com.keepit.model.cache

import com.keepit.common.cache.{ CacheStatistics, FortyTwoCachePlugin, JsonCacheImpl, Key }
import com.keepit.common.logging.AccessLog
import com.keepit.shoebox.model.ids.UserSessionExternalId
import com.keepit.model.view.UserSessionView

import scala.concurrent.duration.Duration

case class UserSessionViewExternalIdKey(externalId: UserSessionExternalId) extends Key[UserSessionView] {
  override val version = 1
  val namespace = "user_session_view_by_external_id"
  def toKey(): String = externalId.id
}

class UserSessionViewExternalIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserSessionViewExternalIdKey, UserSessionView](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

