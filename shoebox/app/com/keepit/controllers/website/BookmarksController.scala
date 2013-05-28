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

private case class BasicCollection(id: ExternalId[Collection], name: String)

private object BasicCollection {
  private implicit val externalIdFormat = ExternalId.format[Collection]
  implicit val format = Json.format[BasicCollection]

  def fromCollection(c: Collection): BasicCollection = BasicCollection(c.externalId, c.name)
}

@Singleton
class BookmarksController @Inject() (
    db: Database,
    userRepo: UserRepo,
    bookmarkRepo: BookmarkRepo,
    collectionRepo: CollectionRepo,
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
