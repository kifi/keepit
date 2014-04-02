package com.keepit.model

import org.joda.time.DateTime
import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.common.cache.{JsonCacheImpl, PrimitiveCacheImpl, FortyTwoCachePlugin, Key, CacheStatistics}
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration.Duration
import com.keepit.serializer.TraversableFormat

case class KeepToCollection(
  id: Option[Id[KeepToCollection]] = None,
  keepId: Id[Keep],
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

case class CollectionsForKeepKey(keepId: Id[Keep]) extends Key[Seq[Id[Collection]]] {
  override val version = 2
  val namespace = "collections_for_bookmark"
  def toKey(): String = keepId.toString
}

class CollectionsForKeepCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[CollectionsForKeepKey, Seq[Id[Collection]]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)(TraversableFormat.seq(Id.format[Collection]))

// NOTE: the following code is left here as comment for record that we used the name space, bookmarks_for_collection. bump up the version number if you want to use it again.
//case class BookmarksForCollectionKey(collectionId: Id[Collection]) extends Key[Seq[Id[Keep]]] {
//  override val version = 1
//  val namespace = "bookmarks_for_collection"
//  def toKey(): String = collectionId.toString
//}

case class KeepCountForCollectionKey(collectionId: Id[Collection]) extends Key[Int] {
  override val version = 1
  val namespace = "bookmark_count_for_collection"
  def toKey(): String = collectionId.toString
}

class KeepCountForCollectionCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration, Duration)*)
  extends PrimitiveCacheImpl[KeepCountForCollectionKey, Int](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)

object KeepToCollectionStates extends States[KeepToCollection]
