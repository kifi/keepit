package com.keepit.model

import com.google.inject.{Provider, Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.{State, Id}
import com.keepit.common.db.slick.DBSession.{RSession, RWSession}
import com.keepit.common.time.Clock
import scala.Some
import scala.slick.lifted.Query

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
  def delete(id: Id[KeepToCollection])(implicit session: RWSession): Unit
}

@Singleton
class KeepToCollectionRepoImpl @Inject() (
   collectionsForBookmarkCache: CollectionsForBookmarkCache,
   collectionRepo: CollectionRepo,
   bookmarkRepoProvider: Provider[BookmarkRepoImpl],
   val db: DataBaseComponent,
   val clock: Clock)
  extends DbRepo[KeepToCollection] with KeepToCollectionRepo {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  private lazy val bookmarkRepo = bookmarkRepoProvider.get

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
    collectionRepo.collectionChanged(model.collectionId, model.isActive)
    super.save(model)
  }

  def count(collId: Id[Collection])(implicit session: RSession): Int = {
    import bookmarkRepo.{stateTypeMapper => bookmarkStateMapper}
    Query((for {
      c <- table
      b <- bookmarkRepo.table if b.id === c.bookmarkId && c.collectionId === collId &&
         b.state === BookmarkStates.ACTIVE && c.state === KeepToCollectionStates.ACTIVE
    } yield c).length).firstOption.getOrElse(0)
  }
  
  def delete(id: Id[KeepToCollection])(implicit session: RWSession): Unit = {
    val q = (for(r <- table if r.id === id) yield r)
    q.firstOption.map{ ktc => collectionRepo.collectionChanged(ktc.collectionId, false) }
    q.delete
  }

}
