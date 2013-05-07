package com.keepit.controllers.website

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.WebsiteController
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.model._

import play.api.libs.json._

@Singleton
class BookmarksController @Inject() (
    db: Database,
    bookmarkRepo: BookmarkRepo,
    actionAuthenticator: ActionAuthenticator
  )
  extends WebsiteController(actionAuthenticator) {

  implicit val writesBookmark = new Writes[Bookmark] {
    def writes(bookmark: Bookmark) = Json.obj(
      "id" -> bookmark.externalId.id,
      "title" -> bookmark.title,
      "url" -> bookmark.url,
      "isPrivate" -> bookmark.isPrivate,
      "createdAt" -> bookmark.createdAt
    )
  }

  def allKeeps() = AuthenticatedJsonAction { request =>
    Ok(Json.obj(
      "keeps" -> db.readOnly { implicit s => bookmarkRepo.getByUser(request.userId) }
    ))
  }

  def numKeeps() = AuthenticatedJsonAction { request =>
    Ok(Json.obj(
      "numKeeps" -> db.readOnly { implicit s => bookmarkRepo.getCountByUser(request.userId) }
    ))
  }
}
