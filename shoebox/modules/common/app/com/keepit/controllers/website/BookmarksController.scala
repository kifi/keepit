package com.keepit.controllers.website

import scala.concurrent.ExecutionContext.Implicits.global

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.WebsiteController
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.db.slick.Database
import com.keepit.common.social.{BasicUser, BasicUserRepo}
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.search.SearchServiceClient

import play.api.libs.json._

private case class BasicCollection(id: Option[ExternalId[Collection]], name: String, keeps: Option[Int])

private object BasicCollection {
  private implicit val externalIdFormat = ExternalId.format[Collection]
  implicit val format = Json.format[BasicCollection]

  def fromCollection(c: Collection, keeps: Option[Int] = None): BasicCollection =
    BasicCollection(Some(c.externalId), c.name, keeps)
}

@Singleton
class BookmarksController @Inject() (
    db: Database,
    userRepo: UserRepo,
    bookmarkRepo: BookmarkRepo,
    collectionRepo: CollectionRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    basicUserRepo: BasicUserRepo,
    userValueRepo: UserValueRepo,
    searchClient: SearchServiceClient,
    actionAuthenticator: ActionAuthenticator
  )
  extends WebsiteController(actionAuthenticator) {

  implicit val writesKeepInfo = new Writes[(Bookmark, Set[BasicUser], Set[ExternalId[Collection]])] {
    def writes(info: (Bookmark, Set[BasicUser], Set[ExternalId[Collection]])) = Json.obj(
      "id" -> info._1.externalId.id,
      "title" -> info._1.title,
      "url" -> info._1.url,
      "isPrivate" -> info._1.isPrivate,
      "createdAt" -> info._1.createdAt,
      "keepers" -> info._2,
      "collections" -> info._3.map(_.id)
    )
  }

  private def getBookmarkExternalId(id: String): Option[ExternalId[Bookmark]] = {
    db.readOnly { implicit s => ExternalId.asOpt[Bookmark](id).flatMap(bookmarkRepo.getOpt) } map (_.externalId)
  }

  private def getCollectionByExternalId(userId: Id[User], id: String): Option[Collection] = {
    db.readOnly { implicit s =>
      ExternalId.asOpt[Collection](id).flatMap(collectionRepo.getByUserAndExternalId(userId, _))
    }
  }

  val CollectionOrderingKey = "user_collection_ordering"

  def getCollectionOrdering() = AuthenticatedJsonAction { request =>
    val uid = request.userId
    implicit val collectionIdFormat = ExternalId.format[Collection]
    val allCollectionIds = db.readOnly { implicit s => collectionRepo.getByUser(uid).map(_.externalId) }
    val orderedCollectionIds = Json.fromJson[Seq[ExternalId[Collection]]](Json.parse {
      db.readOnly { implicit s => userValueRepo.getValue(uid, CollectionOrderingKey) } getOrElse {
        db.readWrite { implicit s =>
          userValueRepo.setValue(uid, CollectionOrderingKey, Json.stringify(Json.toJson(allCollectionIds)))
        }
      }
    }).get
    Ok(Json.obj(
      "collectionIds" -> allCollectionIds.sortBy(orderedCollectionIds.indexOf(_))
    ))
  }

  def setCollectionOrdering() = AuthenticatedJsonAction { request =>
    implicit val collectionIdFormat = ExternalId.format[Collection]
    val uid = request.userId
    request.body.asJson.flatMap(Json.fromJson[Seq[ExternalId[Collection]]](_).asOpt) map { orderedIds =>
      val allCollectionIds = db.readOnly { implicit s => collectionRepo.getByUser(uid).map(_.externalId) }
      val newCollectionIds = allCollectionIds.sortBy(orderedIds.indexOf(_))
      db.readWrite { implicit s =>
        userValueRepo.setValue(uid, CollectionOrderingKey, Json.stringify(Json.toJson(newCollectionIds)))
      }
      Ok(Json.obj(
        "collectionIds" -> newCollectionIds
      ))
    } getOrElse {
      BadRequest(Json.obj(
        "error" -> "Could not parse JSON array of collection ids from request body"
      ))
    }
  }

  def allKeeps(before: Option[String], after: Option[String], collection: Option[String], count: Int) =
      AuthenticatedJsonAction { request =>
    (before map getBookmarkExternalId, after map getBookmarkExternalId,
        collection map { getCollectionByExternalId(request.userId, _) }) match {
      case (Some(None), _, _) => BadRequest(Json.obj("error" -> s"Invalid id for before: ${before.get}"))
      case (_, Some(None), _) => BadRequest(Json.obj("error" -> s"Invalid id for after: ${after.get}"))
      case (_, _, Some(None)) => BadRequest(Json.obj("error" -> s"Invalid id for collection: ${collection.get}"))
      case (beforeId, afterId, coll) => Async {
        val keeps = db.readOnly { implicit s =>
          bookmarkRepo.getByUser(request.userId, beforeId.flatten, afterId.flatten, coll.flatten.map(_.id.get), count)
        }
        searchClient.sharingUserInfo(request.userId, keeps.map(_.uriId)) map { infos =>
          db.readOnly { implicit s =>
            val idToBasicUser = infos.flatMap(_.sharingUserIds).distinct.map(id => id -> basicUserRepo.load(id)).toMap
            val collIdToExternalId = collectionRepo.getByUser(request.userId).map(c => c.id.get -> c.externalId).toMap
            (keeps zip infos).map { case (keep, info) =>
              val collIds =
                keepToCollectionRepo.getCollectionsForBookmark(keep.id.get).flatMap(collIdToExternalId.get).toSet
              (keep, info.sharingUserIds map idToBasicUser, collIds)
            }
          }
        } map { keepsInfo =>
          Ok(Json.obj(
            "collection" -> coll.flatten.map(BasicCollection fromCollection _),
            "before" -> before,
            "after" -> after,
            "keeps" -> keepsInfo
          ))
        }
      }
    }
  }

  def allCollections() = AuthenticatedJsonAction { request =>
    Ok(Json.obj(
      "collections" -> db.readOnly { implicit s =>
        collectionRepo.getByUser(request.userId).map { c =>
          val count = keepToCollectionRepo.count(c.id.get)
          BasicCollection fromCollection(c, Some(count))
        }
      }
    ))
  }

  def saveCollection(id: String) = AuthenticatedJsonAction { request =>
    request.body.asJson.flatMap(Json.fromJson[BasicCollection](_).asOpt) map { bc =>
      bc.copy(id = ExternalId.asOpt(id))
    } map { bc =>
      val name = bc.name
      if (name.length <= Collection.MaxNameLength) {
        db.readWrite { implicit s =>
          val existingCollection = collectionRepo.getByUserAndName(request.userId, name, None)
          val existingExternalId = existingCollection collect { case c if c.isActive => c.externalId }
          if (existingExternalId.isEmpty || existingExternalId == bc.id) {
            bc.id map { id =>
              collectionRepo.getByUserAndExternalId(request.userId, id) map { coll =>
                val newColl = collectionRepo.save(coll.copy(externalId = id, name = name))
                Ok(Json.toJson(BasicCollection.fromCollection(newColl)))
              } getOrElse {
                NotFound(Json.obj("error" -> s"Collection not found for id $id"))
              }
            } getOrElse {
              val newColl = collectionRepo.save(existingCollection
                  map { _.copy(name = name, state = CollectionStates.ACTIVE) }
                  getOrElse Collection(userId = request.userId, name = name))
              Ok(Json.toJson(BasicCollection.fromCollection(newColl)))
            }
          } else {
            BadRequest(Json.obj("error" -> s"Collection '$name' already exists with id ${existingExternalId.get}"))
          }
        }
      } else {
        BadRequest(Json.obj("error" -> s"Name '$name' is too long (maximum ${Collection.MaxNameLength} chars)"))
      }
    } getOrElse {
      BadRequest(Json.obj("error" -> "Could not parse collection from body"))
    }
  }

  def deleteCollection(id: ExternalId[Collection]) = AuthenticatedJsonAction { request =>
    db.readOnly { implicit s => collectionRepo.getByUserAndExternalId(request.userId, id) } map { coll =>
      db.readWrite { implicit s =>
        collectionRepo.save(coll.copy(state = CollectionStates.INACTIVE))
      }
      Ok(Json.obj())
    } getOrElse {
      NotFound(Json.obj("error" -> s"Collection not found for id $id"))
    }
  }

  def removeKeepsFromCollection(id: ExternalId[Collection]) = AuthenticatedJsonAction { request =>
    implicit val externalIdFormat = ExternalId.format[Bookmark]
    db.readOnly { implicit s => collectionRepo.getByUserAndExternalId(request.userId, id) } map { collection =>
      request.body.asJson.flatMap(Json.fromJson[Set[ExternalId[Bookmark]]](_).asOpt) map { keepExtIds =>
        val removed = db.readWrite { implicit s =>
          val keepIds = keepExtIds.flatMap(bookmarkRepo.getOpt(_).map(_.id.get))
          keepToCollectionRepo.getByCollection(collection.id.get, excludeState = None) collect {
            case ktc if ktc.state != KeepToCollectionStates.INACTIVE && keepIds.contains(ktc.bookmarkId) =>
              keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.INACTIVE))
          }
        }
        Ok(Json.obj("removed" -> removed.size))
      } getOrElse {
        BadRequest(Json.obj("error" -> "Could not parse JSON keep ids from body"))
      }
    } getOrElse {
      NotFound(Json.obj("error" -> s"Collection not found for id $id"))
    }
  }

  def keepToCollection(id: ExternalId[Collection], removeOthers: Boolean = false) =AuthenticatedJsonAction { request =>
    implicit val externalIdFormat = ExternalId.format[Bookmark]
    db.readOnly { implicit s => collectionRepo.getByUserAndExternalId(request.userId, id) } map { collection =>
      request.body.asJson.flatMap(Json.fromJson[Set[ExternalId[Bookmark]]](_).asOpt) map { keepExtIds =>
        val (added, removed) = db.readWrite { implicit s =>
          val keepIds = keepExtIds.flatMap(bookmarkRepo.getOpt(_).map(_.id.get))
          val existing = keepToCollectionRepo.getByCollection(collection.id.get, excludeState = None)
          val activated = existing collect {
            case ktc if ktc.state == KeepToCollectionStates.INACTIVE && keepIds.contains(ktc.bookmarkId) =>
              keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.ACTIVE))
          }
          val created = (keepIds diff existing.map(_.bookmarkId).toSet) map { bid =>
            keepToCollectionRepo.save(KeepToCollection(bookmarkId = bid, collectionId = collection.id.get))
          }
          val removed = removeOthers match {
            case true => existing.collect {
              case ktc if ktc.state == KeepToCollectionStates.ACTIVE && !keepIds.contains(ktc.bookmarkId) =>
                keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.INACTIVE))
            }
            case false => Seq()
          }
          (activated ++ created, removed)
        }
        Ok(Json.obj("added" -> added.size, "removed" -> removed.size))
      } getOrElse {
        BadRequest(Json.obj("error" -> "Could not parse JSON keep ids from body"))
      }
    } getOrElse {
      NotFound(Json.obj("error" -> s"Collection not found for id $id"))
    }
  }

  def numKeeps() = AuthenticatedJsonAction { request =>
    Ok(Json.obj(
      "numKeeps" -> db.readOnly { implicit s => bookmarkRepo.getCountByUser(request.userId) }
    ))
  }

  def mutualKeeps(id: ExternalId[User]) = AuthenticatedJsonAction { request =>
    Ok(Json.obj(
      "mutualKeeps" -> db.readOnly { implicit s => bookmarkRepo.getNumMutual(request.userId, userRepo.get(id).id.get) }
    ))
  }
}
