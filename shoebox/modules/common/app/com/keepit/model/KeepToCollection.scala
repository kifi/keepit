package com.keepit.model

import scala.concurrent.duration._
import scala.slick.lifted.Query

import org.joda.time.DateTime

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.cache.{FortyTwoCache, FortyTwoCachePlugin, Key}
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.time._

import play.api.libs.json.Json

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
}

@ImplementedBy(classOf[KeepToCollectionRepoImpl])
trait KeepToCollectionRepo extends Repo[KeepToCollection] {
  def getCollectionsForBookmark(bookmarkId: Id[Bookmark])(implicit session: RSession): Seq[Id[Collection]]
  def getBookmarksInCollection(collectionId: Id[Collection])(implicit session: RSession): Seq[Id[Bookmark]]
  def getByBookmark(keepId: Id[Bookmark],
      excludeState: Option[State[KeepToCollection]] = Some(KeepToCollectionStates.INACTIVE))
      (implicit session: RSession): Seq[KeepToCollection]
  def getByCollection(collId: Id[Collection],
      excludeState: Option[State[KeepToCollection]] = Some(KeepToCollectionStates.INACTIVE))
      (implicit session: RSession): Seq[KeepToCollection]
  def count(collId: Id[Collection])(implicit session: RSession): Int
}

case class CollectionsForBookmarkKey(bookmarkId: Id[Bookmark]) extends Key[Seq[Id[Collection]]] {
  val namespace = "collections_for_bookmark"
  def toKey(): String = bookmarkId.toString
}

class CollectionsForBookmarkCache @Inject() (val repo: FortyTwoCachePlugin)
    extends FortyTwoCache[CollectionsForBookmarkKey, Seq[Id[Collection]]] {
  val ttl = 1 day
  private implicit val idFormat = Id.format[Collection]
  def deserialize(obj: Any): Seq[Id[Collection]] = parseJson(obj)
  def serialize(c: Seq[Id[Collection]]): Any = Json.toJson(c)
}

@Singleton
class KeepToCollectionRepoImpl @Inject() (
  collectionsForBookmarkCache: CollectionsForBookmarkCache,
  collectionRepo: CollectionRepo,
  val db: DataBaseComponent,
  val clock: Clock)
  extends DbRepo[KeepToCollection] with KeepToCollectionRepo {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override def invalidateCache(ktc: KeepToCollection)(implicit session: RSession): KeepToCollection = {
    collectionsForBookmarkCache.set(CollectionsForBookmarkKey(ktc.bookmarkId),
      (for (c <- table if c.bookmarkId === ktc.bookmarkId && c.state === KeepToCollectionStates.ACTIVE)
        yield c.collectionId).list)
    ktc
  }

  override val table = new RepoTable[KeepToCollection](db, "keep_to_collection") {
    def bookmarkId = column[Id[Bookmark]]("bookmark_id", O.NotNull)
    def collectionId = column[Id[Collection]]("collection_id", O.NotNull)
    def * = id.? ~ bookmarkId ~ collectionId ~ state ~ createdAt ~ updatedAt <> (KeepToCollection.apply _,
        KeepToCollection.unapply _)
  }
  def getCollectionsForBookmark(bookmarkId: Id[Bookmark])(implicit session: RSession): Seq[Id[Collection]] =
    collectionsForBookmarkCache.getOrElse(CollectionsForBookmarkKey(bookmarkId)) {
      (for (c <- table if c.bookmarkId === bookmarkId && c.state === KeepToCollectionStates.ACTIVE)
        yield c.collectionId).list
    }

  def getBookmarksInCollection(collectionId: Id[Collection])(implicit session: RSession): Seq[Id[Bookmark]] =
    (for (c <- table if c.collectionId === collectionId && c.state === KeepToCollectionStates.ACTIVE)
      yield c.bookmarkId).list

  def getByBookmark(keepId: Id[Bookmark],
      excludeState: Option[State[KeepToCollection]] = Some(KeepToCollectionStates.INACTIVE))
      (implicit session: RSession): Seq[KeepToCollection] =
    (for (c <- table if c.bookmarkId === keepId && c.state =!= excludeState.getOrElse(null)) yield c).list

  def getByCollection(collId: Id[Collection],
      excludeState: Option[State[KeepToCollection]] = Some(KeepToCollectionStates.INACTIVE))
      (implicit session: RSession): Seq[KeepToCollection] =
    (for (c <- table if c.collectionId === collId && c.state =!= excludeState.getOrElse(null)) yield c).list

  override def save(model: KeepToCollection)(implicit session: RWSession): KeepToCollection = {
    collectionRepo.keepsChanged(model.collectionId, model.isActive)
    super.save(model)
  }

  def count(collId: Id[Collection])(implicit session: RSession): Int = {
    Query((for (c <- table if c.collectionId === collId) yield c).length).firstOption.getOrElse(0)
  }
}

object KeepToCollectionStates extends States[KeepToCollection]
