package com.keepit.controllers.website

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.WebsiteController
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.social.{BasicUser, BasicUserRepo}
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.search.SearchServiceClient

import play.api.libs.json._

@Singleton
class BookmarksController @Inject() (
    db: Database,
    userRepo: UserRepo,
    bookmarkRepo: BookmarkRepo,
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

  def allKeeps(before: Option[String], count: Int) = AuthenticatedJsonAction { request =>
    Async {
      val keeps = db.readOnly { implicit s =>
        bookmarkRepo.getByUser(request.userId, before.map(ExternalId[Bookmark](_)), count)
      }
      searchClient.sharingUserInfo(request.userId, keeps.map(_.uriId)) map { infos =>
        val idToBasicUser = db.readOnly { implicit s =>
          infos.flatMap(_.sharingUserIds).distinct.map(id => id -> basicUserRepo.load(id)).toMap
        }
        (keeps zip infos).map { case (keep, info) => (keep, info.sharingUserIds map idToBasicUser) }
      } map { keepsWithKeepers =>
        Ok(Json.obj(
          "before" -> before,
          "keeps" -> keepsWithKeepers
        ))
      }
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
