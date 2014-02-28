package com.keepit.commanders

import com.keepit.common.logging.Logging
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.search.SearchServiceClient
import play.api.libs.json.Json
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.Inject
import com.keepit.heimdal._
import scala.collection.mutable.ArrayBuffer

case class BasicCollection(id: Option[ExternalId[Collection]], name: String, keeps: Option[Int])

object BasicCollection {
  implicit val externalIdFormat = ExternalId.format[Collection]
  implicit val format = Json.format[BasicCollection]
  def fromCollection(c: Collection, keeps: Option[Int] = None): BasicCollection =
    BasicCollection(Some(c.externalId), c.name, keeps)
}

case class CollectionSaveFail(message: String) extends AnyVal

class CollectionCommander @Inject() (
  db: Database,
  collectionRepo: CollectionRepo,
  userValueRepo: UserValueRepo,
  searchClient: SearchServiceClient,
  keepToCollectionRepo: KeepToCollectionRepo,
  keptAnalytics: KeepingAnalytics) extends Logging {

  val CollectionOrderingKey = "user_collection_ordering"

  def allCollections(sort: String, userId: Id[User]) = {
    log.info(s"Getting all collections for $userId (sort $sort)")
    val unsortedCollections = db.readOnly { implicit s =>
      collectionRepo.getByUser(userId).map { c =>
        val count = collectionRepo.getBookmarkCount(c.id.get)
        BasicCollection fromCollection(c, Some(count))
      }}
    log.info(s"Sorting collections for $userId")
    val collections = sort match {
      case "user" =>
        userSort(userId, unsortedCollections)
      case _ => // default is "last_kept"
        unsortedCollections
    }
    log.info(s"Returning collection and keep counts for $userId")
    collections
  }

  def updateCollectionOrdering(uid: Id[User])(implicit s: RWSession): Seq[ExternalId[Collection]] = {
    setCollectionOrdering(uid, getCollectionOrdering(uid))
  }

  private def userSort(uid: Id[User], unsortedCollections: Seq[BasicCollection]): Seq[BasicCollection] = db.readWrite { implicit s =>
    implicit val externalIdFormat = ExternalId.format[Collection]
    log.info(s"Getting collection ordering for user $uid")
    userValueRepo.getValue(uid, CollectionOrderingKey).map{ value => Json.fromJson[Seq[ExternalId[Collection]]](Json.parse(value)).get } match {
      case Some(orderedCollectionIds) =>
        val buf = new ArrayBuffer[BasicCollection](unsortedCollections.size)
        val collectionMap = unsortedCollections.map(c => c.id.get -> c).toMap
        val newCollectionIds = (collectionMap.keySet -- orderedCollectionIds)
        if (newCollectionIds.nonEmpty) {
          unsortedCollections.foreach{ c => if (newCollectionIds.contains(c.id.get)) buf += c }
        }
        orderedCollectionIds.foreach{ id => collectionMap.get(id).foreach{ c => buf += c } }
        buf
      case None =>
        val allCollectionIds = unsortedCollections.map(_.id.get)
        log.info(s"Updating collection ordering for user $uid: $allCollectionIds")
        userValueRepo.setValue(uid, CollectionOrderingKey, Json.stringify(Json.toJson(allCollectionIds)))
        unsortedCollections
    }
  }

  def getCollectionOrdering(uid: Id[User])(implicit s: RWSession): Seq[ExternalId[Collection]] = {
    implicit val externalIdFormat = ExternalId.format[Collection]
    log.info(s"Getting collection ordering for user $uid")
    userValueRepo.getValue(uid, CollectionOrderingKey).map{ value =>
      Json.fromJson[Seq[ExternalId[Collection]]](Json.parse(value)).get
    } getOrElse {
      val allCollectionIds = collectionRepo.getByUser(uid).map(_.externalId)
      log.info(s"Updating collection ordering for user $uid: $allCollectionIds")
      userValueRepo.setValue(uid, CollectionOrderingKey, Json.stringify(Json.toJson(allCollectionIds)))
      allCollectionIds
    }
  }

  def setCollectionOrdering(uid: Id[User],
      order: Seq[ExternalId[Collection]])(implicit s: RWSession): Seq[ExternalId[Collection]] = {
    implicit val externalIdFormat = ExternalId.format[Collection]
    val allCollectionIds = collectionRepo.getByUser(uid).map(_.externalId)
    val newCollectionIds = allCollectionIds.sortBy(order.indexOf(_))
    userValueRepo.setValue(uid, CollectionOrderingKey, Json.stringify(Json.toJson(newCollectionIds)))
    newCollectionIds
  }

  def saveCollection(id: String, userId: Id[User], collectionOpt: Option[BasicCollection])(implicit context: HeimdalContext): Either[BasicCollection, CollectionSaveFail] = {
    val saved: Option[Either[BasicCollection, CollectionSaveFail]] = collectionOpt map { basicCollection =>
      basicCollection.copy(id = ExternalId.asOpt(id))
    } map { basicCollection =>
      val name = basicCollection.name.trim.replaceAll("""\s+""", " ")
      if (name.length <= Collection.MaxNameLength) {
        db.readWrite { implicit s =>
          val existingCollection = collectionRepo.getByUserAndName(userId, name, None)
          val existingExternalId = existingCollection collect { case c if c.isActive => c.externalId }
          if (existingExternalId.isEmpty || existingExternalId == basicCollection.id) {
            s.onTransactionSuccess{ searchClient.updateURIGraph() }
            basicCollection.id map { id =>
              //
              collectionRepo.getByUserAndExternalId(userId, id) map { coll =>
                val newColl = collectionRepo.save(coll.copy(externalId = id, name = name))
                updateCollectionOrdering(userId)
                keptAnalytics.renamedTag(coll, newColl, context)
                Left(BasicCollection.fromCollection(newColl))
              } getOrElse {
                Right(CollectionSaveFail(s"Tag with name $id not found"))
              }
            } getOrElse {
              val newColl = collectionRepo.save(existingCollection
                  map { _.copy(name = name, state = CollectionStates.ACTIVE) }
                  getOrElse Collection(userId = userId, name = name))
              updateCollectionOrdering(userId)
              keptAnalytics.createdTag(newColl, context)
              Left(BasicCollection.fromCollection(newColl))
            }
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
    keptAnalytics.deletedTag(collection, context)
    searchClient.updateURIGraph()
  }
}
