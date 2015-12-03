package com.keepit.commanders

import com.keepit.common.logging.Logging
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import play.api.libs.json.{ JsArray, Json }
import com.google.inject.Inject
import com.keepit.heimdal._
import scala.collection.mutable.ArrayBuffer
import com.keepit.common.cache._
import com.keepit.common.logging.AccessLog
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import com.keepit.common.time._

case class BasicCollectionByIdKey(id: Id[Collection]) extends Key[BasicCollection] {
  override val version = 1
  val namespace = "basic_collection"
  def toKey(): String = id.toString
}

class BasicCollectionByIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[BasicCollectionByIdKey, BasicCollection](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class CollectionSaveFail(message: String) extends AnyVal

class CollectionCommander @Inject() (
    db: Database,
    collectionRepo: CollectionRepo,
    userValueRepo: UserValueRepo,
    searchClient: SearchServiceClient,
    libraryAnalytics: LibraryAnalytics,
    basicCollectionCache: BasicCollectionByIdCache,
    keepToCollectionRepo: KeepToCollectionRepo,
    implicit val executionContext: ExecutionContext,
    clock: Clock) extends Logging {

  def getCount(userId: Id[User]) = {
    db.readOnlyMaster { implicit s =>
      collectionRepo.count(userId)
    }
  }

  def allCollections(sort: String, userId: Id[User]) = {
    log.info(s"Getting all collections for $userId (sort $sort)")
    val unsortedCollections = db.readOnlyMaster { implicit s =>
      val colls = collectionRepo.getUnfortunatelyIncompleteTagSummariesByUser(userId)
      val bmCounts = collectionRepo.getBookmarkCounts(colls.map(_.id).toSet)
      colls.map { c => BasicCollection.fromCollection(c, bmCounts.get(c.id).orElse(Some(0))) }
    }
    log.info(s"Sorting collections for $userId")
    val collections = sort match {
      case "num_keeps" =>
        unsortedCollections.sortBy(_.keeps)(Ordering[Option[Int]].reverse)
      case _ => // default is "last_kept"
        unsortedCollections
    }
    log.info(s"Returning collection and keep counts for $userId")
    collections
  }

  def pageCollections(sort: String, offset: Int, pageSize: Int, userId: Id[User]) = {
    log.info(s"Getting all collections for $userId (sort $sort)")
    db.readOnlyMaster { implicit s =>
      sort match {
        case "num_keeps" => collectionRepo.getByUserSortedByNumKeeps(userId, offset, pageSize)
        case "name" => collectionRepo.getByUserSortedByName(userId, offset, pageSize)
        case _ => collectionRepo.getByUserSortedByLastKept(userId, offset, pageSize) // default is "last_kept"
      }
    }.map { case (collectionSummary, keepCount) => BasicCollection.fromCollection(collectionSummary, Some(keepCount)) }
  }

  def deleteCollection(collection: Collection)(implicit context: HeimdalContext): Unit = {
    db.readWrite { implicit s =>
      collectionRepo.save(collection.copy(state = CollectionStates.INACTIVE))
    }
    searchClient.updateKeepIndex()
  }

  def undeleteCollection(collection: Collection)(implicit context: HeimdalContext): Unit = {
    db.readWrite { implicit s =>
      collectionRepo.save(collection.copy(state = CollectionStates.ACTIVE, createdAt = clock.now()))
    }
    searchClient.updateKeepIndex()
  }

  def getBasicCollections(ids: Seq[Id[Collection]]): Seq[BasicCollection] = {
    db.readOnlyMaster { implicit session =>
      ids.map { id =>
        basicCollectionCache.getOrElse(BasicCollectionByIdKey(id)) {
          val collection = collectionRepo.get(id)
          BasicCollection.fromCollection(collection.summary)
        }
      }
    }
  }
  def copyKeepTags(source: Keep, dest: Keep)(implicit session: RWSession): Unit = {
    keepToCollectionRepo.getByKeep(source.id.get).foreach { ktc =>
      val newKtcTemplate = ktc.withKeepId(dest.id.get)
      val existingKtcOpt = keepToCollectionRepo.getOpt(dest.id.get, ktc.collectionId)
      keepToCollectionRepo.save(newKtcTemplate.copy(id = existingKtcOpt.map(_.id.get)))
      collectionRepo.collectionChanged(ktc.collectionId, inactivateIfEmpty = true)
    }
  }

  def deactivateKeepTags(keep: Keep)(implicit session: RWSession): Unit = {
    keepToCollectionRepo.getByKeep(keep.id.get).foreach(keepToCollectionRepo.deactivate)
  }

}
