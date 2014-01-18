package com.keepit.model

import org.joda.time.DateTime
import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, Key, CacheStatistics}
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration.Duration
import com.keepit.serializer.TraversableFormat

case class KeepToCollection(
  id: Option[Id[KeepToCollection]] = None,
  bookmarkId: Id[Bookmark],
  collectionId: Id[Collection],
  state: State[KeepToCollection] = KeepToCollectionStates.ACTIVE,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime
  ) extends ModelWithState[KeepToCollection] {
  def isActive: Boolean = state == KeepToCollectionStates.ACTIVE
  def withId(id: Id[KeepToCollection]): KeepToCollection = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): KeepToCollection = this.copy(updatedAt = now)
  def inactivate(): KeepToCollection = this.copy(state = KeepToCollectionStates.INACTIVE)
}

case class CollectionsForBookmarkKey(bookmarkId: Id[Bookmark]) extends Key[Seq[Id[Collection]]] {
  override val version = 2
  val namespace = "collections_for_bookmark"
  def toKey(): String = bookmarkId.toString
}

class CollectionsForBookmarkCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[CollectionsForBookmarkKey, Seq[Id[Collection]]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)(TraversableFormat.seq(Id.format[Collection]))

case class BookmarksForCollectionKey(collectionId: Id[Collection]) extends Key[Seq[Id[Bookmark]]] {
  override val version = 1
  val namespace = "bookmarks_for_collection"
  def toKey(): String = collectionId.toString
}

class BookmarksForCollectionCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[BookmarksForCollectionKey, Seq[Id[Bookmark]]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)(TraversableFormat.seq(Id.format[Bookmark]))

object KeepToCollectionStates extends States[KeepToCollection]
