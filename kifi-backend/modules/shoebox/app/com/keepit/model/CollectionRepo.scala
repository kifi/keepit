package com.keepit.model

import com.keepit.common.actor.ActorInstance
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.{ SequencingActor, SchedulingProperties, SequencingPlugin }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick._
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.eliza.ElizaServiceClient
import scala.util.Try
import play.api.libs.json.Json
import com.keepit.common.logging.Logging
import scala.slick.jdbc.StaticQuery
import com.keepit.typeahead.{ UserHashtagTypeaheadUserIdKey, UserHashtagTypeaheadCache, HashtagTypeahead }

@ImplementedBy(classOf[CollectionRepoImpl])
trait CollectionRepo extends Repo[Collection] with ExternalIdColumnFunction[Collection] with SeqNumberFunction[Collection] {
  def getUnfortunatelyIncompleteTagsByUser(userId: Id[User])(implicit session: RSession): Seq[Collection]
  def getByUserAndExternalId(userId: Id[User], externalId: ExternalId[Collection],
    excludeState: Option[State[Collection]] = Some(CollectionStates.INACTIVE))(implicit session: RSession): Option[Collection]
  def getByUserAndName(userId: Id[User], name: Hashtag,
    excludeState: Option[State[Collection]] = Some(CollectionStates.INACTIVE))(implicit session: RSession): Option[Collection]
  def getBookmarkCount(collId: Id[Collection])(implicit session: RSession): Int
  def count(userId: Id[User])(implicit session: RSession): Int
  def getBookmarkCounts(collIds: Set[Id[Collection]])(implicit session: RSession): Map[Id[Collection], Int]
  def collectionChanged(collectionId: Id[Collection], isNewKeep: Boolean = false, inactivateIfEmpty: Boolean)(implicit session: RWSession): Collection
  def getHashtagsByKeepId(keepId: Id[Keep])(implicit session: RSession): Set[Hashtag]
  def getByUserSortedByLastKept(userId: Id[User], page: Int, size: Int)(implicit session: RSession): Seq[(CollectionSummary, Int)]
  def getByUserSortedByNumKeeps(userId: Id[User], page: Int, size: Int)(implicit session: RSession): Seq[(CollectionSummary, Int)]
  def getByUserSortedByName(userId: Id[User], page: Int, size: Int)(implicit session: RSession): Seq[(CollectionSummary, Int)]
  def getAllTagsByUserSortedByNumKeeps(userId: Id[User])(implicit session: RSession): Seq[(Hashtag, Int)]
}

@Singleton
class CollectionRepoImpl @Inject() (
  val userCollectionsCache: UserCollectionsCache,
  val userCollectionSummariesCache: UserCollectionSummariesCache,
  val bookmarkCountForCollectionCache: KeepCountForCollectionCache,
  val keepToCollectionRepo: KeepToCollectionRepo,
  val elizaServiceClient: ElizaServiceClient,
  val db: DataBaseComponent,
  val clock: Clock,
  userHashtagTypeaheadCache: UserHashtagTypeaheadCache)
    extends DbRepo[Collection] with CollectionRepo with ExternalIdColumnDbFunction[Collection] with SeqNumberDbFunction[Collection] with Logging {

  import db.Driver.simple._

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
    model.id.foreach { id =>
      val keepToCollections = keepToCollectionRepo.getByCollection(id)
      keepToCollections.foreach(keepToCollectionRepo.deleteCache)
      bookmarkCountForCollectionCache.remove(KeepCountForCollectionKey(id))
    }
  }

  def getUnfortunatelyIncompleteTagsByUser(userId: Id[User])(implicit session: RSession): Seq[Collection] =
    userCollectionsCache.getOrElse(UserCollectionsKey(userId)) {
      (for (c <- rows if c.userId === userId && c.state === CollectionStates.ACTIVE) yield c).sortBy(r => (r.lastKeptTo.desc, r.id)).take(500).list
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
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
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
    val newModel = model.copy(seq = deferredSeqNum())
    super.save(newModel)
  }

  def collectionChanged(collectionId: Id[Collection], isNewKeep: Boolean = false, inactivateIfEmpty: Boolean)(implicit session: RWSession): Collection = {
    val collection = get(collectionId)
    session.onTransactionSuccess {
      userHashtagTypeaheadCache.remove(UserHashtagTypeaheadUserIdKey(collection.userId))
    }
    if (isNewKeep) {
      save(collection withLastKeptTo clock.now())
    } else if (inactivateIfEmpty && getBookmarkCount(collectionId) == 0) {
      save(collection.copy(state = CollectionStates.INACTIVE))
    } else {
      save(collection)
    }
  }

  def getHashtagsByKeepId(keepId: Id[Keep])(implicit session: RSession): Set[Hashtag] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query = sql"select DISTINCT c.name from keep_to_collection kc, collection c where kc.bookmark_id = ${keepId} and c.id = kc.collection_id and c.state='#${CollectionStates.ACTIVE}' and kc.state='#${KeepToCollectionStates.ACTIVE}'"

    query.as[String].list.map(tag => Hashtag(tag)).toSet
  }

  private def pageTagsByUserQuery[S](userId: Id[User], page: Int, size: Int, sortBy: String) = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query = sql"select c.id, c.external_id, c.name, count(kc.id) as keep_count, c.last_kept_to from collection c, keep_to_collection kc, bookmark b where kc.collection_id=c.id and kc.bookmark_id=b.id and c.user_id=${userId} and b.state='#${KeepStates.ACTIVE}' and kc.state='#${KeepToCollectionStates.ACTIVE}' and c.state=${CollectionStates.ACTIVE} group by c.id order by #${sortBy} limit ${size} offset ${page * size}"
    query.as[(Id[Collection], ExternalId[Collection], Hashtag, Int, Option[DateTime])]
  }

  private val sortByName = "name"
  private val sortByLastKeptTo = "last_kept_to DESC, name"
  private val sortByKeepCount = "keep_count DESC, name"

  def getByUserSortedByLastKept(userId: Id[User], page: Int, size: Int)(implicit session: RSession): Seq[(CollectionSummary, Int)] = {
    pageTagsByUserQuery(userId, page, size, sortByLastKeptTo).list.map { row =>
      (CollectionSummary(row._1, row._2, row._3), row._4)
    }
  }

  def getByUserSortedByNumKeeps(userId: Id[User], page: Int, size: Int)(implicit session: RSession): Seq[(CollectionSummary, Int)] = {
    pageTagsByUserQuery(userId, page, size, sortByKeepCount).list.map { row =>
      (CollectionSummary(row._1, row._2, row._3), row._4)
    }
  }
  def getByUserSortedByName(userId: Id[User], page: Int, size: Int)(implicit session: RSession): Seq[(CollectionSummary, Int)] = {
    pageTagsByUserQuery(userId, page, size, sortByName).list.map { row =>
      (CollectionSummary(row._1, row._2, row._3), row._4)
    }
  }

  def getAllTagsByUserSortedByNumKeeps(userId: Id[User])(implicit session: RSession): Seq[(Hashtag, Int)] = {
    pageTagsByUserQuery(userId, 0, Int.MaxValue, sortByKeepCount).list.map { row => (row._3, row._4) }
  }
}

trait CollectionSeqPlugin extends SequencingPlugin

class CollectionSeqPluginImpl @Inject() (override val actor: ActorInstance[CollectionSeqActor], override val scheduling: SchedulingProperties)
  extends CollectionSeqPlugin

@Singleton
class CollectionSeqAssigner @Inject() (db: Database, repo: CollectionRepo, airbrake: AirbrakeNotifier)
  extends DbSequenceAssigner[Collection](db, repo, airbrake)

class CollectionSeqActor @Inject() (assigner: CollectionSeqAssigner, airbrake: AirbrakeNotifier)
  extends SequencingActor(assigner, airbrake)
