package com.keepit.model

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ SequenceNumber, State, ExternalId, Id }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.eliza.ElizaServiceClient
import scala.util.Try
import play.api.libs.json.Json
import com.keepit.common.logging.Logging
import scala.slick.jdbc.StaticQuery

@ImplementedBy(classOf[CollectionRepoImpl])
trait CollectionRepo extends Repo[Collection] with ExternalIdColumnFunction[Collection] with SeqNumberFunction[Collection] {
  def getUnfortunatelyIncompleteTagsByUser(userId: Id[User])(implicit session: RSession): Seq[Collection]
  def getUnfortunatelyIncompleteTagSummariesByUser(userId: Id[User])(implicit session: RSession): Seq[CollectionSummary]
  def getByUserAndExternalId(userId: Id[User], externalId: ExternalId[Collection],
    excludeState: Option[State[Collection]] = Some(CollectionStates.INACTIVE))(implicit session: RSession): Option[Collection]
  def getByUserAndName(userId: Id[User], name: Hashtag,
    excludeState: Option[State[Collection]] = Some(CollectionStates.INACTIVE))(implicit session: RSession): Option[Collection]
  def getBookmarkCount(collId: Id[Collection])(implicit session: RSession): Int
  def count(userId: Id[User])(implicit session: RSession): Int
  def getBookmarkCounts(collIds: Set[Id[Collection]])(implicit session: RSession): Map[Id[Collection], Int]
  def getCollectionsChanged(num: SequenceNumber[Collection], limit: Int)(implicit session: RSession): Seq[Collection]
  def collectionChanged(collectionId: Id[Collection], isNewKeep: Boolean = false)(implicit session: RWSession): Collection
  def getTagsByKeepId(keepId: Id[Keep])(implicit session: RSession): Set[Hashtag]
}

@Singleton
class CollectionRepoImpl @Inject() (
  val userCollectionsCache: UserCollectionsCache,
  val userCollectionSummariesCache: UserCollectionSummariesCache,
  val bookmarkCountForCollectionCache: KeepCountForCollectionCache,
  val keepToCollectionRepo: KeepToCollectionRepo,
  val elizaServiceClient: ElizaServiceClient,
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[Collection] with CollectionRepo with ExternalIdColumnDbFunction[Collection] with SeqNumberDbFunction[Collection] with Logging {

  import db.Driver.simple._

  private val sequence = db.getSequence[Collection]("collection_sequence")

  type RepoImpl = CollectionTable

  class CollectionTable(tag: Tag) extends RepoTable[Collection](db, tag, "collection") with ExternalIdColumn[Collection] with SeqNumberColumn[Collection] {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def name = column[Hashtag]("name", O.NotNull)
    def lastKeptTo = column[Option[DateTime]]("last_kept_to", O.Nullable)
    def * = (id.?, externalId, userId, name, state, createdAt, updatedAt, lastKeptTo, seq) <> (
      (Collection.apply _).tupled, Collection.unapply _)
  }

  def table(tag: Tag) = new CollectionTable(tag)
  initTable()

  override def invalidateCache(collection: Collection)(implicit session: RSession): Unit = {
    deleteCache(collection)
  }

  override def deleteCache(model: Collection)(implicit session: RSession): Unit = {
    userCollectionsCache.remove(UserCollectionsKey(model.userId))
    userCollectionSummariesCache.remove(UserCollectionSummariesKey(model.userId))
    model.id map { id =>
      bookmarkCountForCollectionCache.remove(KeepCountForCollectionKey(id))
    }
  }

  def getUnfortunatelyIncompleteTagsByUser(userId: Id[User])(implicit session: RSession): Seq[Collection] =
    userCollectionsCache.getOrElse(UserCollectionsKey(userId)) {
      (for (c <- rows if c.userId === userId && c.state === CollectionStates.ACTIVE) yield c).sortBy(r => (r.lastKeptTo.desc, r.id)).take(500).list
    }

  def getUnfortunatelyIncompleteTagSummariesByUser(userId: Id[User])(implicit session: RSession): Seq[CollectionSummary] =
    userCollectionSummariesCache.getOrElse(UserCollectionSummariesKey(userId)) {
      import StaticQuery.interpolation
      import scala.collection.JavaConversions._
      sql"select id, external_id, name from collection where user_id = $userId and state = '#${CollectionStates.ACTIVE}' order by last_kept_to desc, id limit 500".
        as[(Id[Collection], ExternalId[Collection], Hashtag)].list.map { row =>
          CollectionSummary(row._1, row._2, row._3)
        }
    }

  def getByUserAndExternalId(userId: Id[User], externalId: ExternalId[Collection],
    excludeState: Option[State[Collection]] = Some(CollectionStates.INACTIVE))(implicit session: RSession): Option[Collection] =
    (for {
      c <- rows if c.userId === userId && c.externalId === externalId && c.state =!= excludeState.getOrElse(null)
    } yield c).firstOption

  def getByUserAndName(userId: Id[User], name: Hashtag,
    excludeState: Option[State[Collection]] = Some(CollectionStates.INACTIVE))(implicit session: RSession): Option[Collection] =
    (for (
      c <- rows if c.userId === userId && c.name === name
        && c.state =!= excludeState.getOrElse(null)
    ) yield c).firstOption

  def getBookmarkCount(collId: Id[Collection])(implicit session: RSession): Int = {
    bookmarkCountForCollectionCache.getOrElse(KeepCountForCollectionKey(collId)) { keepToCollectionRepo.count(collId) }
  }

  def count(userId: Id[User])(implicit session: RSession): Int = {
    import StaticQuery.interpolation
    import scala.collection.JavaConversions._
    sql"select count(*) from collection where user_id = $userId".as[Int].first
  }

  def getBookmarkCounts(collIds: Set[Id[Collection]])(implicit session: RSession): Map[Id[Collection], Int] = {
    val keys = collIds.map(KeepCountForCollectionKey)
    bookmarkCountForCollectionCache.bulkGetOrElse(keys) { missing =>
      missing.map { key => key -> keepToCollectionRepo.count(key.collectionId) }.toMap
    }.map { case (key, count) => key.collectionId -> count }
  }

  override def save(model: Collection)(implicit session: RWSession): Collection = {
    val newModel = model.copy(seq = sequence.incrementAndGet())
    session.onTransactionSuccess {
      Try {
        val tag = SendableTag.from(model.summary)
        if (model.state == CollectionStates.INACTIVE) {
          elizaServiceClient.sendToUser(model.userId, Json.arr("remove_tag", tag))
        } else if (model.id == None) {
          elizaServiceClient.sendToUser(model.userId, Json.arr("create_tag", tag))
        } else {
          elizaServiceClient.sendToUser(model.userId, Json.arr("rename_tag", tag))
        }
      }
    }
    super.save(newModel)
  }

  def collectionChanged(collectionId: Id[Collection], isNewKeep: Boolean = false)(implicit session: RWSession): Collection = {
    val collection = get(collectionId)
    if (isNewKeep) {
      save(collection withLastKeptTo clock.now())
    } else {
      val keepCount = getBookmarkCount(collectionId)
      if (keepCount > 0) save(collection) else save(collection.copy(state = CollectionStates.INACTIVE))
    }
  }

  def getCollectionsChanged(num: SequenceNumber[Collection], limit: Int)(implicit session: RSession): Seq[Collection] = super.getBySequenceNumber(num, limit)

  def getTagsByKeepId(keepId: Id[Keep])(implicit session: RSession): Set[Hashtag] = {
    import StaticQuery.interpolation
    val query = sql"select c.name from keep_to_collection kc, collection c where kc.bookmark_id = ${keepId} and c.id = kc.collection_id and c.state=${CollectionStates.ACTIVE} and kc.state=${KeepToCollectionStates.ACTIVE}"

    query.as[String].list.map(tag => Hashtag(tag)).toSet
  }
}

