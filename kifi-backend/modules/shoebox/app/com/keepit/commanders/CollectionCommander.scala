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
        val count = keepToCollectionRepo.count(c.id.get)
        BasicCollection fromCollection(c, Some(count))
      }}
    log.info(s"Sorting collections for $userId")
    val collections = sort match {
      case "user" =>
        val orderedCollectionIds = db.readWrite { implicit s => getCollectionOrdering(userId) }
        unsortedCollections.sortBy(c => orderedCollectionIds.indexOf(c.id.get))
      case _ => // default is "last_kept"
        unsortedCollections
    }
    log.info(s"Returning collection and keep counts for $userId")
    collections
  }

  def updateCollectionOrdering(uid: Id[User])(implicit s: RWSession): Seq[ExternalId[Collection]] = {
    setCollectionOrdering(uid, getCollectionOrdering(uid))
  }

  def getCollectionOrdering(uid: Id[User])(implicit s: RWSession): Seq[ExternalId[Collection]] = {
    implicit val externalIdFormat = ExternalId.format[Collection]
    log.info(s"Getting collection ordering for user $uid")
    val allCollectionIds = collectionRepo.getByUser(uid).map(_.externalId)
    Json.fromJson[Seq[ExternalId[Collection]]](Json.parse {
      userValueRepo.getValueStringOpt(uid, CollectionOrderingKey) getOrElse {
        log.info(s"Updating collection ordering for user $uid: $allCollectionIds")
        userValueRepo.setValue(uid, CollectionOrderingKey, Json.stringify(Json.toJson(allCollectionIds)))
      }
    }).get
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
