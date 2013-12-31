package com.keepit.model

import scala.slick.lifted.Query

import com.google.inject.{Provider, Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.DBSession.{RSession, RWSession}
import com.keepit.common.db.slick._
import com.keepit.common.db.{State, Id}
import com.keepit.common.time._

@ImplementedBy(classOf[KeepToCollectionRepoImpl])
trait KeepToCollectionRepo extends Repo[KeepToCollection] {
  def getCollectionsForBookmark(bookmarkId: Id[Bookmark])(implicit session: RSession): Seq[Id[Collection]]
  def getBookmarksInCollection(collectionId: Id[Collection])(implicit session: RSession): Seq[Id[Bookmark]]
  def getUriIdsInCollection(collectionId: Id[Collection])(implicit session: RSession): Seq[BookmarkUriAndTime]
  def getByBookmark(keepId: Id[Bookmark],
                    excludeState: Option[State[KeepToCollection]] = Some(KeepToCollectionStates.INACTIVE))
                   (implicit session: RSession): Seq[KeepToCollection]
  def getByCollection(collId: Id[Collection],
                      excludeState: Option[State[KeepToCollection]] = Some(KeepToCollectionStates.INACTIVE))
                     (implicit session: RSession): Seq[KeepToCollection]
  def count(collId: Id[Collection])(implicit session: RSession): Int
  def remove(bookmarkId: Id[Bookmark], collectionId: Id[Collection])(implicit session: RWSession): Unit
  def getOpt(bookmarkId: Id[Bookmark], collectionId: Id[Collection])(implicit session: RSession): Option[KeepToCollection]
}

@Singleton
class KeepToCollectionRepoImpl @Inject() (
   collectionsForBookmarkCache: CollectionsForBookmarkCache,
   bookmarksForCollectionCache: BookmarksForCollectionCache,
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
    bookmarksForCollectionCache.set(BookmarksForCollectionKey(ktc.collectionId),
      (for (c <- table if c.collectionId === ktc.collectionId && c.state === KeepToCollectionStates.ACTIVE)
      yield c.bookmarkId).list)
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
    val ktc = super.save(model)
    collectionRepo.collectionChanged(ktc.collectionId, ktc.isActive)
    ktc
  }

  def count(collId: Id[Collection])(implicit session: RSession): Int = {
    import bookmarkRepo.{stateTypeMapper => bookmarkStateMapper}
    Query((for {
      c <- table
      b <- bookmarkRepo.table if b.id === c.bookmarkId && c.collectionId === collId &&
         b.state === BookmarkStates.ACTIVE && c.state === KeepToCollectionStates.ACTIVE
    } yield c).length).firstOption.getOrElse(0)
  }

  def getOpt(bookmarkId: Id[Bookmark], collectionId: Id[Collection])(implicit session: RSession): Option[KeepToCollection] = {
    (for(r <- table if r.bookmarkId === bookmarkId && r.collectionId === collectionId) yield r).firstOption
  }

  def remove(bookmarkId: Id[Bookmark], collectionId: Id[Collection])(implicit session: RWSession): Unit = {
    val q = for(r <- table if r.bookmarkId === bookmarkId && r.collectionId === collectionId) yield r
    q.list.map { ktc => //there should be only [0, 1], iterating on possibly more for safty
      save(ktc.inactivate())
      collectionRepo.collectionChanged(ktc.collectionId, false)
    }
  }

  def getUriIdsInCollection(collectionId: Id[Collection])(implicit session: RSession): Seq[BookmarkUriAndTime] = {
    import bookmarkRepo.{stateTypeMapper => bookmarkStateMapper}
    val res = (for {
      c <- table
      b <- bookmarkRepo.table if b.id === c.bookmarkId && c.collectionId === collectionId &&
                                 b.state === BookmarkStates.ACTIVE &&
                                 c.state === KeepToCollectionStates.ACTIVE
    } yield (b.uriId, b.createdAt)) list;

    res map {r => BookmarkUriAndTime(r._1, r._2)}
  }
}
