package com.keepit.model

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
  private[model] def count(collId: Id[Collection])(implicit session: RSession): Int
  def remove(bookmarkId: Id[Bookmark], collectionId: Id[Collection])(implicit session: RWSession): Unit
  def getOpt(bookmarkId: Id[Bookmark], collectionId: Id[Collection])(implicit session: RSession): Option[KeepToCollection]
}

@Singleton
class KeepToCollectionRepoImpl @Inject() (
   collectionsForBookmarkCache: CollectionsForBookmarkCache,
   bookmarkRepoProvider: Provider[BookmarkRepoImpl],
   val db: DataBaseComponent,
   val clock: Clock)
  extends DbRepo[KeepToCollection] with KeepToCollectionRepo {

  import db.Driver.simple._

  private lazy val bookmarkRepo = bookmarkRepoProvider.get

  override def invalidateCache(ktc: KeepToCollection)(implicit session: RSession): Unit = {
    collectionsForBookmarkCache.set(CollectionsForBookmarkKey(ktc.bookmarkId),
      (for (c <- rows if c.bookmarkId === ktc.bookmarkId && c.state === KeepToCollectionStates.ACTIVE)
      yield c.collectionId).list)
  }

  override def deleteCache(ktc: KeepToCollection)(implicit session: RSession): Unit = {
    collectionsForBookmarkCache.remove(CollectionsForBookmarkKey(ktc.bookmarkId))
  }

  type RepoImpl = KeepToCollectionTable
  class KeepToCollectionTable(tag: Tag) extends RepoTable[KeepToCollection](db, tag, "keep_to_collection") {
    def bookmarkId = column[Id[Bookmark]]("bookmark_id", O.NotNull)
    def collectionId = column[Id[Collection]]("collection_id", O.NotNull)
    def * = (id.?, bookmarkId, collectionId, state, createdAt, updatedAt) <> ((KeepToCollection.apply _).tupled,
      KeepToCollection.unapply _)
  }

  def table(tag: Tag) = new KeepToCollectionTable(tag)
  initTable()

  def getCollectionsForBookmark(bookmarkId: Id[Bookmark])(implicit session: RSession): Seq[Id[Collection]] =
    collectionsForBookmarkCache.getOrElse(CollectionsForBookmarkKey(bookmarkId)) {
      (for (c <- rows if c.bookmarkId === bookmarkId && c.state === KeepToCollectionStates.ACTIVE)
      yield c.collectionId).list
    }

  def getBookmarksInCollection(collectionId: Id[Collection])(implicit session: RSession): Seq[Id[Bookmark]] =
      (for (c <- rows if c.collectionId === collectionId && c.state === KeepToCollectionStates.ACTIVE)
      yield c.bookmarkId).list

  def getByBookmark(keepId: Id[Bookmark],
                    excludeState: Option[State[KeepToCollection]] = Some(KeepToCollectionStates.INACTIVE))
                   (implicit session: RSession): Seq[KeepToCollection] =
    (for (c <- rows if c.bookmarkId === keepId && c.state =!= excludeState.getOrElse(null)) yield c).list

  def getByCollection(collId: Id[Collection],
                      excludeState: Option[State[KeepToCollection]] = Some(KeepToCollectionStates.INACTIVE))
                     (implicit session: RSession): Seq[KeepToCollection] =
    (for (c <- rows if c.collectionId === collId && c.state =!= excludeState.getOrElse(null)) yield c).list

  private[model] def count(collId: Id[Collection])(implicit session: RSession): Int = {
    import bookmarkRepo.db.Driver.simple._
    Query((for {
      c <- this.rows
      b <- bookmarkRepo.rows if b.id === c.bookmarkId && c.collectionId === collId &&
         b.state === BookmarkStates.ACTIVE && c.state === KeepToCollectionStates.ACTIVE
    } yield c).length).firstOption.getOrElse(0)
  }

  def getOpt(bookmarkId: Id[Bookmark], collectionId: Id[Collection])(implicit session: RSession): Option[KeepToCollection] = {
    (for(r <- rows if r.bookmarkId === bookmarkId && r.collectionId === collectionId) yield r).firstOption
  }

  def remove(bookmarkId: Id[Bookmark], collectionId: Id[Collection])(implicit session: RWSession): Unit = {
    val q = for(r <- rows if r.bookmarkId === bookmarkId && r.collectionId === collectionId) yield r
    q.list.map { ktc => //there should be only [0, 1], iterating on possibly more for safty
      save(ktc.inactivate())
    }
  }

  def getUriIdsInCollection(collectionId: Id[Collection])(implicit session: RSession): Seq[BookmarkUriAndTime] = {
    import bookmarkRepo.db.Driver.simple._
    val res = (for {
      c <- this.rows
      b <- bookmarkRepo.rows if b.id === c.bookmarkId && c.collectionId === collectionId &&
                                 b.state === BookmarkStates.ACTIVE &&
                                 c.state === KeepToCollectionStates.ACTIVE
    } yield (b.uriId, b.createdAt)) list;

    res map {r => BookmarkUriAndTime(r._1, r._2)}
  }
}
