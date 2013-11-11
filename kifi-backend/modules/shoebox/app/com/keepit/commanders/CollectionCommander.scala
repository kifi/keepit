package com.keepit.commanders

import com.keepit.common.logging.Logging
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.model._
import com.keepit.common.time._

import play.api.Play.current
import play.api.libs.json.{JsObject, Json}

import com.google.inject.Inject
import com.keepit.common.net.URI
import com.keepit.controllers.core.NetworkInfoLoader
import com.keepit.common.social.BasicUserRepo
import com.keepit.social.BasicUser
import com.keepit.common.analytics.{EventPersister, Event, EventFamilies, Events}
import play.api.libs.concurrent.Akka

case class BasicCollection(id: Option[ExternalId[Collection]], name: String, keeps: Option[Int])

object BasicCollection {
  implicit val externalIdFormat = ExternalId.format[Collection]
  implicit val format = Json.format[BasicCollection]
  def fromCollection(c: Collection, keeps: Option[Int] = None): BasicCollection =
    BasicCollection(Some(c.externalId), c.name, keeps)
}

class CollectionCommander @Inject() (
  db: Database,
  collectionRepo: CollectionRepo,
  userValueRepo: UserValueRepo,
  keepToCollectionRepo: KeepToCollectionRepo) extends Logging {

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
      userValueRepo.getValue(uid, CollectionOrderingKey) getOrElse {
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
}
