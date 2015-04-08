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

case class BasicCollection(id: Option[ExternalId[Collection]], name: String, keeps: Option[Int])

object BasicCollection {
  implicit val externalIdFormat = ExternalId.format[Collection]
  implicit val format = Json.format[BasicCollection]
  def fromCollection(c: CollectionSummary, keeps: Option[Int] = None): BasicCollection =
    BasicCollection(Some(c.externalId), c.name.tag, keeps)
}

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
    implicit val executionContext: ExecutionContext,
    clock: Clock) extends Logging {

  def allCollections(sort: String, userId: Id[User]) = {
    log.info(s"Getting all collections for $userId (sort $sort)")
    val unsortedCollections = db.readOnlyMaster { implicit s =>
      val colls = collectionRepo.getUnfortunatelyIncompleteTagSummariesByUser(userId)
      val bmCounts = collectionRepo.getBookmarkCounts(colls.map(_.id).toSet)
      colls.map { c => BasicCollection.fromCollection(c, bmCounts.get(c.id).orElse(Some(0))) }
    }
    log.info(s"Sorting collections for $userId")
    val collections = sort match {
      case "user" =>
        userSort(userId, unsortedCollections)
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

  def updateCollectionOrdering(uid: Id[User])(implicit s: RWSession): Seq[ExternalId[Collection]] = {
    setCollectionOrdering(uid, getCollectionOrdering(uid))
  }

  private def userSort(uid: Id[User], unsortedCollections: Seq[BasicCollection]): Seq[BasicCollection] = {
    implicit val externalIdFormat = ExternalId.format[Collection]
    log.info(s"Getting collection ordering for user $uid")
    val ordering = db.readOnlyMaster { implicit s => userValueRepo.getValueStringOpt(uid, UserValueName.USER_COLLECTION_ORDERING) }
    ordering.map {
      value => Json.fromJson[Seq[ExternalId[Collection]]](Json.parse(value)).get
    } match {
      case Some(orderedCollectionIds) =>
        val buf = new ArrayBuffer[BasicCollection](unsortedCollections.size)
        val collectionMap = unsortedCollections.map(c => c.id.get -> c).toMap
        val newCollectionIds = (collectionMap.keySet -- orderedCollectionIds)
        if (newCollectionIds.nonEmpty) {
          unsortedCollections.foreach { c => if (newCollectionIds.contains(c.id.get)) buf += c }
        }
        orderedCollectionIds.foreach { id => collectionMap.get(id).foreach { c => buf += c } }
        buf
      case None =>
        val allCollectionIds = unsortedCollections.map(_.id.get)
        log.info(s"Updating collection ordering for user $uid: $allCollectionIds")
        db.readWrite(attempts = 3) { implicit s =>
          userValueRepo.setValue(uid, UserValueName.USER_COLLECTION_ORDERING, Json.stringify(Json.toJson(allCollectionIds)))
        }
        unsortedCollections
    }
  }

  def getCollectionOrdering(uid: Id[User])(implicit s: RWSession): Seq[ExternalId[Collection]] = {
    implicit val externalIdFormat = ExternalId.format[Collection]
    log.info(s"Getting collection ordering for user $uid")
    userValueRepo.getValueStringOpt(uid, UserValueName.USER_COLLECTION_ORDERING).map { value =>
      Json.fromJson[Seq[ExternalId[Collection]]](Json.parse(value)).get
    } getOrElse {
      val allCollectionIds = collectionRepo.getUnfortunatelyIncompleteTagSummariesByUser(uid).map(_.externalId)
      log.info(s"Updating collection ordering for user $uid: $allCollectionIds")
      userValueRepo.setValue(uid, UserValueName.USER_COLLECTION_ORDERING, Json.stringify(Json.toJson(allCollectionIds)))
      allCollectionIds
    }
  }

  def setCollectionOrdering(uid: Id[User],
    order: Seq[ExternalId[Collection]])(implicit s: RWSession): Seq[ExternalId[Collection]] = {
    implicit val externalIdFormat = ExternalId.format[Collection]
    val allCollectionIds = collectionRepo.getUnfortunatelyIncompleteTagSummariesByUser(uid).map(_.externalId)
    val newCollectionIds = allCollectionIds.sortBy(order.indexOf(_))
    userValueRepo.setValue(uid, UserValueName.USER_COLLECTION_ORDERING, Json.stringify(Json.toJson(newCollectionIds)))
    newCollectionIds
  }

  def setCollectionIndexOrdering(uid: Id[User], tagId: ExternalId[Collection], newIndex: Int): Seq[ExternalId[Collection]] = {
    implicit val externalIdFormat = ExternalId.format[Collection]

    val (allCollectionIds, orderStr) = db.readOnlyMaster { implicit s =>
      val allCollectionIds = collectionRepo.getUnfortunatelyIncompleteTagSummariesByUser(uid).zipWithIndex
      val orderStr = userValueRepo.getValue(uid, UserValues.tagOrdering)
      (allCollectionIds, orderStr)
    }
    val orderStrArr = orderStr.as[JsArray].value.map(_.as[ExternalId[Collection]])

    val tupleBuffer = allCollectionIds.sortWith {
      case (first, second) =>
        val firstIdx = orderStrArr.indexOf(first._1.externalId)
        val secondIdx = orderStrArr.indexOf(second._1.externalId)
        if (firstIdx != -1 && secondIdx == -1) {
          true
        } else if (firstIdx == -1 && secondIdx != -1) {
          false
        } else if (firstIdx == -1 && secondIdx == -1) { // both not found
          first._2 < second._2
        } else { // both found
          firstIdx < secondIdx
        }
    }.toBuffer
    val idsBuffer = tupleBuffer.unzip._1.map(_.externalId)
    idsBuffer.remove(idsBuffer.indexOf(tagId))
    idsBuffer.insert(newIndex, tagId)

    val newOrdering = idsBuffer.toSeq
    db.readWrite { implicit s =>
      userValueRepo.setValue(uid, UserValueName.USER_COLLECTION_ORDERING, Json.stringify(Json.toJson(newOrdering)))
    }
    newOrdering
  }

  def saveCollection(userId: Id[User], collectionOpt: Option[BasicCollection])(implicit context: HeimdalContext): Either[BasicCollection, CollectionSaveFail] = {
    val saved: Option[Either[BasicCollection, CollectionSaveFail]] = collectionOpt map { basicCollection =>
      val name = Hashtag(basicCollection.name.trim.replaceAll("""\s+""", " "))
      if (name.tag.length <= Collection.MaxNameLength) {
        db.readWrite { implicit s =>
          val existingCollection = collectionRepo.getByUserAndName(userId, name, None)
          val existingExternalId = existingCollection collect { case c if c.isActive => c.externalId }
          if (existingExternalId.isEmpty) {
            s.onTransactionSuccess { searchClient.updateKeepIndex() }
            val newColl = collectionRepo.save(Collection(userId = userId, name = name))
            updateCollectionOrdering(userId)
            libraryAnalytics.createdTag(newColl, context)
            Left(BasicCollection.fromCollection(newColl.summary))
          } else {
            Right(CollectionSaveFail(s"Tag '$name' already exists"))
          }
        }
      } else {
        Right(CollectionSaveFail(s"Name '$name' is too long (maximum ${Collection.MaxNameLength} characters)"))
      }
    }
    saved.getOrElse {
      Right(CollectionSaveFail("Could not parse tag from body"))
    }
  }

  def deleteCollection(collection: Collection)(implicit context: HeimdalContext): Unit = {
    db.readWrite { implicit s =>
      collectionRepo.save(collection.copy(state = CollectionStates.INACTIVE))
      updateCollectionOrdering(collection.userId)
    }
    libraryAnalytics.deletedTag(collection, context)
    searchClient.updateKeepIndex()
  }

  def undeleteCollection(collection: Collection)(implicit context: HeimdalContext): Unit = {
    db.readWrite { implicit s =>
      collectionRepo.save(collection.copy(state = CollectionStates.ACTIVE, createdAt = clock.now()))
      updateCollectionOrdering(collection.userId)
    }
    libraryAnalytics.undeletedTag(collection, context)
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
}
