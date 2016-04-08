package com.keepit.commanders

import com.keepit.common.logging.Logging
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import play.api.libs.json.{ JsArray, Json }
import com.google.inject.{ Provider, Inject }
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

class CollectionCommander @Inject() (
    db: Database,
    collectionRepo: CollectionRepo,
    userValueRepo: UserValueRepo,
    searchClient: SearchServiceClient,
    libraryAnalytics: LibraryAnalytics,
    basicCollectionCache: BasicCollectionByIdCache,
    keepToCollectionRepo: KeepToCollectionRepo,
    implicit val executionContext: ExecutionContext,
    keepCommander: Provider[KeepCommander],
    clock: Clock) extends Logging {

  def getCount(userId: Id[User]) = {
    db.readOnlyMaster { implicit s =>
      collectionRepo.count(userId)
    }
  }

  // legacy, users have too many tags to reliably return them all
  def allCollections(sort: String, userId: Id[User]) = {
    pageCollections(userId, 0, 1000, TagSorting(sort))
  }

  def pageCollections(userId: Id[User], offset: Int, pageSize: Int, sort: TagSorting) = {
    db.readOnlyMaster { implicit s =>
      sort match {
        case TagSorting.NumKeeps => collectionRepo.getByUserSortedByNumKeeps(userId, offset, pageSize)
        case TagSorting.Name => collectionRepo.getByUserSortedByName(userId, offset, pageSize)
        case TagSorting.LastKept => collectionRepo.getByUserSortedByLastKept(userId, offset, pageSize)
      }
    }.map { case (collectionSummary, keepCount) => BasicCollection.fromCollection(collectionSummary, Some(keepCount)) }
  }

}

sealed trait TagSorting { def name: String }
object TagSorting {
  case object NumKeeps extends TagSorting { val name = "num_keeps" }
  case object Name extends TagSorting { val name = "name" }
  case object LastKept extends TagSorting { val name = "last_kept" }

  def apply(str: String) = str match {
    case NumKeeps.name => NumKeeps
    case Name.name => Name
    case _ => LastKept
  }
}

