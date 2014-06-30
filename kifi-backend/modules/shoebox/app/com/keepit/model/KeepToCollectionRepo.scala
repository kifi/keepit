package com.keepit.model

import com.google.inject.{Provider, Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.DBSession.{RSession, RWSession}
import com.keepit.common.db.slick._
import com.keepit.common.db.{State, Id}
import com.keepit.common.time._
import com.keepit.common.performance.timing
import com.keepit.common.healthcheck.AirbrakeNotifier

@ImplementedBy(classOf[KeepToCollectionRepoImpl])
trait KeepToCollectionRepo extends Repo[KeepToCollection] {
  def getCollectionsForKeep(bookmarkId: Id[Keep])(implicit session: RSession): Seq[Id[Collection]]
  def getKeepsInCollection(collectionId: Id[Collection])(implicit session: RSession): Seq[Id[Keep]]
  def getUriIdsInCollection(collectionId: Id[Collection])(implicit session: RSession): Seq[KeepUriAndTime]
  def getByKeep(keepId: Id[Keep],
                    excludeState: Option[State[KeepToCollection]] = Some(KeepToCollectionStates.INACTIVE))
                   (implicit session: RSession): Seq[KeepToCollection]
  def getByCollection(collId: Id[Collection],
                      excludeState: Option[State[KeepToCollection]] = Some(KeepToCollectionStates.INACTIVE))
                     (implicit session: RSession): Seq[KeepToCollection]
  private[model] def count(collId: Id[Collection])(implicit session: RSession): Int
  def remove(keepId: Id[Keep], collectionId: Id[Collection])(implicit session: RWSession): Unit
  def getOpt(keepId: Id[Keep], collectionId: Id[Collection])(implicit session: RSession): Option[KeepToCollection]
  def insertAll(k2c:Seq[KeepToCollection])(implicit session:RWSession):Unit
}

@Singleton
class KeepToCollectionRepoImpl @Inject() (
   airbrake:AirbrakeNotifier,
   collectionsForKeepCache: CollectionsForKeepCache,
   keepRepoProvider: Provider[KeepRepoImpl],
   val db: DataBaseComponent,
   val clock: Clock)
  extends DbRepo[KeepToCollection] with KeepToCollectionRepo {

  import db.Driver.simple._

  private lazy val keepRepo = keepRepoProvider.get

  override def invalidateCache(ktc: KeepToCollection)(implicit session: RSession): Unit = {
    collectionsForKeepCache.set(CollectionsForKeepKey(ktc.keepId),
      (for (c <- rows if c.bookmarkId === ktc.keepId && c.state === KeepToCollectionStates.ACTIVE)
      yield c.collectionId).list)
  }

  override def deleteCache(ktc: KeepToCollection)(implicit session: RSession): Unit = {
    collectionsForKeepCache.remove(CollectionsForKeepKey(ktc.keepId))
  }

  type RepoImpl = KeepToCollectionTable
  class KeepToCollectionTable(tag: Tag) extends RepoTable[KeepToCollection](db, tag, "keep_to_collection") {
    def bookmarkId = column[Id[Keep]]("bookmark_id", O.NotNull)
    def collectionId = column[Id[Collection]]("collection_id", O.NotNull)
    def * = (id.?, bookmarkId, collectionId, state, createdAt, updatedAt) <> ((KeepToCollection.apply _).tupled,
      KeepToCollection.unapply _)
  }

  def table(tag: Tag) = new KeepToCollectionTable(tag)
  initTable()

  def getCollectionsForKeep(bookmarkId: Id[Keep])(implicit session: RSession): Seq[Id[Collection]] =
    collectionsForKeepCache.getOrElse(CollectionsForKeepKey(bookmarkId)) {
      (for (c <- rows if c.bookmarkId === bookmarkId && c.state === KeepToCollectionStates.ACTIVE)
      yield c).sortBy(c => c.updatedAt).map(_.collectionId).list // todo(martin): we should add a column for explicit ordering of tags
    }

  def getKeepsInCollection(collectionId: Id[Collection])(implicit session: RSession): Seq[Id[Keep]] =
      (for (c <- rows if c.collectionId === collectionId && c.state === KeepToCollectionStates.ACTIVE)
      yield c.bookmarkId).list

  def getByKeep(keepId: Id[Keep],
                    excludeState: Option[State[KeepToCollection]] = Some(KeepToCollectionStates.INACTIVE))
                   (implicit session: RSession): Seq[KeepToCollection] =
    (for (c <- rows if c.bookmarkId === keepId && c.state =!= excludeState.getOrElse(null)) yield c).list

  def getByCollection(collId: Id[Collection],
                      excludeState: Option[State[KeepToCollection]] = Some(KeepToCollectionStates.INACTIVE))
                     (implicit session: RSession): Seq[KeepToCollection] =
    (for (c <- rows if c.collectionId === collId && c.state =!= excludeState.getOrElse(null)) yield c).list

  private[model] def count(collId: Id[Collection])(implicit session: RSession): Int = {
    import keepRepo.db.Driver.simple._
    Query((for {
      c <- this.rows
      b <- keepRepo.rows if b.id === c.bookmarkId && c.collectionId === collId &&
         b.state === KeepStates.ACTIVE && c.state === KeepToCollectionStates.ACTIVE
    } yield c).length).firstOption.getOrElse(0)
  }

  def getOpt(bookmarkId: Id[Keep], collectionId: Id[Collection])(implicit session: RSession): Option[KeepToCollection] = {
    (for(r <- rows if r.bookmarkId === bookmarkId && r.collectionId === collectionId) yield r).firstOption
  }

  def remove(bookmarkId: Id[Keep], collectionId: Id[Collection])(implicit session: RWSession): Unit = {
    val q = for(r <- rows if r.bookmarkId === bookmarkId && r.collectionId === collectionId) yield r
    q.list.map { ktc => //there should be only [0, 1], iterating on possibly more for safty
      save(ktc.inactivate())
    }
  }

  def getUriIdsInCollection(collectionId: Id[Collection])(implicit session: RSession): Seq[KeepUriAndTime] = {
    import keepRepo.db.Driver.simple._
    val res = (for {
      c <- this.rows
      b <- keepRepo.rows if b.id === c.bookmarkId && c.collectionId === collectionId &&
                                 b.state === KeepStates.ACTIVE &&
                                 c.state === KeepToCollectionStates.ACTIVE
    } yield (b.uriId, b.createdAt)) list;

    res map {r => KeepUriAndTime(r._1, r._2)}
  }

  def insertAll(k2c: Seq[KeepToCollection])(implicit session: RWSession): Unit = {
    var i = 0
    k2c.grouped(50).foreach { kc =>
      i += 1
      timing(s"k2c.insertAll($i,${k2c.length})") {
        try {
          rows.insertAll(kc: _*)
        } catch {
          case e:Exception =>
            airbrake.notify(s"k2c.insertAll -- exception ${e} while inserting batch#$i (total=${k2c.length})",e)
            // move on
        } finally {
          kc.foreach(k => collectionsForKeepCache.remove(CollectionsForKeepKey(k.keepId)))
        }
      }
    }
  }

}
