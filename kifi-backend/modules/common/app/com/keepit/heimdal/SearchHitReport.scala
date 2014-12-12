package com.keepit.heimdal

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.logging.AccessLog
import com.keepit.model.{ User, NormalizedURI }
import com.keepit.search.ArticleSearchResult
import play.api.libs.json._
import scala.concurrent.duration.Duration

case class SearchHitReport(
  userId: Id[User],
  uriId: Id[NormalizedURI],
  isOwnKeep: Boolean,
  keepers: Seq[ExternalId[User]],
  origin: String,
  uuid: ExternalId[ArticleSearchResult])

object SearchHitReport {
  implicit val format = Json.format[SearchHitReport]
}

case class SearchHitReportKey(userId: Id[User], uriId: Id[NormalizedURI]) extends Key[SearchHitReport] {
  override val version = 1
  val namespace = "search_hit_report"
  def toKey(): String = userId.id + "#" + uriId.id
}

class SearchHitReportCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SearchHitReportKey, SearchHitReport](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)