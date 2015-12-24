package com.keepit.controllers.site

import com.keepit.common.controller.{ ElizaServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.model.Keep
import com.keepit.common.db.slick.Database
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import play.api.libs.json.Json

import com.google.inject.Inject
import com.keepit.eliza.model._
import com.keepit.eliza.commanders.MessagingCommander

class ChatterController @Inject() (
    messagingCommander: MessagingCommander,
    val userActionsHelper: UserActionsHelper,
    implicit val publicIdConfig: PublicIdConfiguration,
    threadRepo: MessageThreadRepo,
    db: Database) extends UserActions with ElizaServiceController {

  def getChatter() = UserAction.async(parse.tolerantJson) { request =>
    val url = (request.body \ "url").as[String]
    messagingCommander.getChatter(request.user.id.get, Seq(url)).map { res =>
      Ok(res.get(url).map {
        case Seq(keepId) => Json.obj("threads" -> 1, "threadId" -> Keep.publicId(keepId))
        case threadIds => Json.obj("threads" -> threadIds.size)
      }.getOrElse(Json.obj()))
    }
  }
}

