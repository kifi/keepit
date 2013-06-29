package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.common.controller.{ActionAuthenticator, WebsiteController}
import com.keepit.common.db.slick.Database
import com.keepit.model.{CommentRepo, NormalizedURIRepo}

import play.api.libs.json.Json

private case class Chatter(comments: Int, conversations: Int)
private object Chatter { implicit val format = Json.format[Chatter] }

class CommentController @Inject()(
    db: Database,
    normalizedUriRepo: NormalizedURIRepo,
    commentRepo: CommentRepo,
    actionAuthenticator: ActionAuthenticator
  ) extends WebsiteController(actionAuthenticator) {

  def getChatter = AuthenticatedJsonToJsonAction { request =>
    val url = (request.body \ "url").asOpt[String].getOrElse("")
    Ok(Json.toJson(db.readOnly { implicit s =>
      normalizedUriRepo.getByUri(url) map { uri =>
        Chatter(commentRepo.getPublicCount(uri.id.get), commentRepo.getParentMessages(uri.id.get, request.userId).size)
      } getOrElse Chatter(0, 0)
    }))
  }
}
