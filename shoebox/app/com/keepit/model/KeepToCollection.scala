package com.keepit.model

import org.joda.time.DateTime

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.time._

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
}

@Singleton
class KeepToCollectionRepoImpl @Inject() (
  collectionRepo: CollectionRepo,
  val db: DataBaseComponent,
  val clock: Clock)
  extends DbRepo[KeepToCollection] with KeepToCollectionRepo {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[KeepToCollection](db, "keep_to_collection") {
    def bookmarkId = column[Id[Bookmark]]("bookmark_id", O.NotNull)
    def collectionId = column[Id[Collection]]("collection_id", O.NotNull)
    def * = id.? ~ bookmarkId ~ collectionId ~ state ~ createdAt ~ updatedAt <> (KeepToCollection.apply _,
        KeepToCollection.unapply _)
  }
  def getCollectionsForBookmark(bookmarkId: Id[Bookmark])(implicit session: RSession): Seq[Id[Collection]] =
    (for (c <- table if c.bookmarkId === bookmarkId && c.state === KeepToCollectionStates.ACTIVE)
      yield c.collectionId).list

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
    collectionRepo.updateSequenceNumber(model.collectionId)
    super.save(model)
  }
}

object KeepToCollectionStates extends States[KeepToCollection]
