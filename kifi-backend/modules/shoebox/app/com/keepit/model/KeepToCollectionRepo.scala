package com.keepit.model

import com.google.inject.{ Provider, Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ State, Id }
import com.keepit.common.time._
import com.keepit.common.performance.timing
import com.keepit.common.healthcheck.AirbrakeNotifier

import scala.slick.jdbc.StaticQuery

@ImplementedBy(classOf[KeepToCollectionRepoImpl])
trait KeepToCollectionRepo extends Repo[KeepToCollection] {
  def getKeepsForTag(collectionId: Id[Collection],
    excludeState: Option[State[KeepToCollection]] = Some(KeepToCollectionStates.INACTIVE))(implicit seesion: RSession): Seq[Id[Keep]]
  def getByKeep(keepId: Id[Keep],
    excludeState: Option[State[KeepToCollection]] = Some(KeepToCollectionStates.INACTIVE))(implicit session: RSession): Seq[KeepToCollection]
  def getByCollection(collId: Id[Collection],
    excludeState: Option[State[KeepToCollection]] = Some(KeepToCollectionStates.INACTIVE))(implicit session: RSession): Seq[KeepToCollection]
  private[model] def count(collId: Id[Collection])(implicit session: RSession): Int
  def remove(keepId: Id[Keep], collectionId: Id[Collection])(implicit session: RWSession): Unit
  def getOpt(keepId: Id[Keep], collectionId: Id[Collection])(implicit session: RSession): Option[KeepToCollection]
  def deactivate(model: KeepToCollection)(implicit session: RWSession): Unit
}

@Singleton
class KeepToCollectionRepoImpl @Inject() (
  airbrake: AirbrakeNotifier,
  collectionsForKeepCache: CollectionsForKeepCache,
  collectionRepoProvider: Provider[CollectionRepoImpl],
  keepRepoProvider: Provider[KeepRepoImpl],
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[KeepToCollection] with KeepToCollectionRepo {

  import db.Driver.simple._

  private lazy val keepRepo = keepRepoProvider.get
  private lazy val collectionRepo = collectionRepoProvider.get

  override def invalidateCache(ktc: KeepToCollection)(implicit session: RSession): Unit = deleteCache(ktc)

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

  def getCollectionsForKeep(bookmarkId: Id[Keep])(implicit session: RSession): Seq[Id[Collection]] = {
    collectionsForKeepCache.getOrElse(CollectionsForKeepKey(bookmarkId)) {
      val query = for {
        kc <- rows if kc.bookmarkId === bookmarkId && kc.state === KeepToCollectionStates.ACTIVE
        c <- collectionRepo.rows if c.id === kc.collectionId && c.state === CollectionStates.ACTIVE
        k <- keepRepo.rows if k.id === kc.bookmarkId && k.state === KeepStates.ACTIVE
      } yield kc

      query.sortBy(_.updatedAt).map(_.collectionId).list
    }
  }

  def getCollectionsForKeeps(keeps: Seq[Keep])(implicit session: RSession): Seq[Seq[Id[Collection]]] = {
    val keepIds = keeps.collect { case k if k.isActive => k.id.get }.toSet
    val collectionsForKeeps = collectionsForKeepCache.bulkGetOrElse(keepIds map CollectionsForKeepKey) { keys =>
      val missingKeeps = keys.map(_.keepId).toSet
      val query = for {
        kc <- rows if kc.bookmarkId.inSet(missingKeeps) && kc.state === KeepToCollectionStates.ACTIVE
        c <- collectionRepo.rows if c.id === kc.collectionId && c.state === CollectionStates.ACTIVE
      } yield kc

      query.list.groupBy(_.keepId).map {
        case (keepId, keepToCollections) =>
          CollectionsForKeepKey(keepId) -> keepToCollections.sortBy(_.updatedAt).map(_.collectionId) // todo: we should add a column for explicit ordering of tags
      }
    }
    keeps.map { k => collectionsForKeeps.getOrElse(CollectionsForKeepKey(k.id.get), Seq.empty) }
  }

  def getKeepsForTag(collectionId: Id[Collection], excludeState: Option[State[KeepToCollection]] = Some(KeepToCollectionStates.INACTIVE))(implicit seesion: RSession): Seq[Id[Keep]] = {
    val q = for {
      kc <- rows if kc.collectionId === collectionId && kc.state =!= excludeState.orNull
      c <- collectionRepo.rows if c.id === kc.collectionId && c.state === CollectionStates.ACTIVE
      k <- keepRepo.rows if k.id === kc.bookmarkId && k.state === KeepStates.ACTIVE
    } yield k.id
    q.list
  }

  def getByKeep(keepId: Id[Keep],
    excludeState: Option[State[KeepToCollection]] = Some(KeepToCollectionStates.INACTIVE))(implicit session: RSession): Seq[KeepToCollection] =
    (for (c <- rows if c.bookmarkId === keepId && c.state =!= excludeState.orNull) yield c).list

  def getByCollection(collId: Id[Collection],
    excludeState: Option[State[KeepToCollection]] = Some(KeepToCollectionStates.INACTIVE))(implicit session: RSession): Seq[KeepToCollection] =
    (for (c <- rows if c.collectionId === collId && c.state =!= excludeState.orNull) yield c).list

  private[model] def count(collId: Id[Collection])(implicit session: RSession): Int = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val q = sql"""select count(*) from keep_to_collection kc, collection c, bookmark k
      where kc.collection_id = ${collId} and kc.state = '#${KeepToCollectionStates.ACTIVE}' and kc.collection_id = c.id and c.state = '#${CollectionStates.ACTIVE}' and kc.bookmark_id = k.id and k.state = '#${KeepStates.ACTIVE}'"""
    q.as[Int].firstOption.getOrElse(0)
  }

  def getOpt(bookmarkId: Id[Keep], collectionId: Id[Collection])(implicit session: RSession): Option[KeepToCollection] = {
    (for (r <- rows if r.bookmarkId === bookmarkId && r.collectionId === collectionId) yield r).firstOption
  }

  def remove(bookmarkId: Id[Keep], collectionId: Id[Collection])(implicit session: RWSession): Unit = {
    val q = for (r <- rows if r.bookmarkId === bookmarkId && r.collectionId === collectionId) yield r
    q.list.foreach(deactivate)
  }

  def deactivate(model: KeepToCollection)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }

}
