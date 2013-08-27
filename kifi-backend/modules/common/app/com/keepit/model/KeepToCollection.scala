package com.keepit.model

import org.joda.time.DateTime
import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, Key}
import scala.concurrent.duration.Duration
import com.keepit.serializer.TraversableFormat

case class KeepToCollection(
  id: Option[Id[KeepToCollection]] = None,
  bookmarkId: Id[Bookmark],
  collectionId: Id[Collection],
  state: State[KeepToCollection] = KeepToCollectionStates.ACTIVE,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime
  ) extends Model[KeepToCollection] {
  def isActive: Boolean = state == KeepToCollectionStates.ACTIVE
  def withId(id: Id[KeepToCollection]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[KeepToCollection]) = this.copy(state = state)
}

case class CollectionsForBookmarkKey(bookmarkId: Id[Bookmark]) extends Key[Seq[Id[Collection]]] {
  override val version = 2
  val namespace = "collections_for_bookmark"
  def toKey(): String = bookmarkId.toString
}

class CollectionsForBookmarkCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[CollectionsForBookmarkKey, Seq[Id[Collection]]](innermostPluginSettings, innerToOuterPluginSettings:_*)(TraversableFormat.seq(Id.format[Collection]))

object KeepToCollectionStates extends States[KeepToCollection]
