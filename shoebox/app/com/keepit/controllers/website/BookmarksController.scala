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

private case class BasicCollection(id: Option[ExternalId[Collection]], name: String)

private object BasicCollection {
  private implicit val externalIdFormat = ExternalId.format[Collection]
  implicit val format = Json.format[BasicCollection]

  def fromCollection(c: Collection): BasicCollection = BasicCollection(Some(c.externalId), c.name)
}

@Singleton
class BookmarksController @Inject() (
    db: Database,
    userRepo: UserRepo,
    bookmarkRepo: BookmarkRepo,
    collectionRepo: CollectionRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    basicUserRepo: BasicUserRepo,
    searchClient: SearchServiceClient,
    actionAuthenticator: ActionAuthenticator
  )
  extends WebsiteController(actionAuthenticator) {

  implicit val writesKeepWithKeepers = new Writes[(Bookmark, Set[BasicUser])] {
    def writes(info: (Bookmark, Set[BasicUser])) = Json.obj(
      "id" -> info._1.externalId.id,
      "title" -> info._1.title,
      "url" -> info._1.url,
      "isPrivate" -> info._1.isPrivate,
      "createdAt" -> info._1.createdAt,
      "keepers" -> info._2
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

  def allKeeps(before: Option[String], after: Option[String], collection: Option[String], count: Int) =
      AuthenticatedJsonAction { request =>
    (before map getBookmarkExternalId, after map getBookmarkExternalId,
        collection map { getCollectionByExternalId(request.userId, _) }) match {
      case (Some(None), _, _) => BadRequest(s"Invalid id for before: ${before.get}")
      case (_, Some(None), _) => BadRequest(s"Invalid id for after: ${after.get}")
      case (_, _, Some(None)) => BadRequest(s"Invalid id for collection: ${collection.get}")
      case (beforeId, afterId, coll) => Async {
        val keeps = db.readOnly { implicit s =>
          bookmarkRepo.getByUser(request.userId, beforeId.flatten, afterId.flatten, coll.flatten.map(_.id.get), count)
        }
        searchClient.sharingUserInfo(request.userId, keeps.map(_.uriId)) map { infos =>
          val idToBasicUser = db.readOnly { implicit s =>
            infos.flatMap(_.sharingUserIds).distinct.map(id => id -> basicUserRepo.load(id)).toMap
          }
          (keeps zip infos).map { case (keep, info) => (keep, info.sharingUserIds map idToBasicUser) }
        } map { keepsWithKeepers =>
          Ok(Json.obj(
            "collection" -> coll.flatten.map(BasicCollection fromCollection _),
            "before" -> before,
            "after" -> after,
            "keeps" -> keepsWithKeepers
          ))
        }
      }
    }
  }

  def allCollections() = AuthenticatedJsonAction { request =>
    Ok(Json.obj(
      "collections" -> db.readOnly { implicit s =>
        collectionRepo.getByUser(request.userId).map(BasicCollection fromCollection _)
      }
    ))
  }

  def saveCollection(id: String) = AuthenticatedJsonAction { request =>
    request.body.asJson.flatMap(Json.fromJson[BasicCollection](_).asOpt) map { bc =>
      bc.copy(id = ExternalId.asOpt(id))
    } map { bc =>
      db.readWrite { implicit s =>
        val name = bc.name
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
          BadRequest(Json.obj("error" -> s"Collection with name $name already exists (id ${existingExternalId.get})!"))
        }
      }
    } getOrElse {
      BadRequest(Json.obj("error" -> "could not parse collection from body"))
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
