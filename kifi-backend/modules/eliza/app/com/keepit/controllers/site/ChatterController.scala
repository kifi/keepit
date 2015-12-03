package com.keepit.controllers.site

import com.keepit.common.controller.{ ElizaServiceController, UserActions, UserActionsHelper }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.controller.FortyTwoCookies.ImpersonateCookie
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.common.healthcheck.HealthcheckPlugin
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import play.api.libs.json.Json

import com.google.inject.Inject
import com.keepit.eliza.controllers.internal.{ ElizaController, MessagingController }
import com.keepit.eliza._
import com.keepit.eliza.model._
import com.keepit.eliza.commanders.MessagingCommander

class ChatterController @Inject() (
    messagingCommander: MessagingCommander,
    val userActionsHelper: UserActionsHelper,
    threadRepo: MessageThreadRepo,
    db: Database) extends UserActions with ElizaServiceController {

  def getChatter() = UserAction.async(parse.tolerantJson) { request =>
    val url = (request.body \ "url").as[String]
    messagingCommander.getChatter(request.user.id.get, Seq(url)).map { res =>
      Ok(res.get(url).map {
        case Seq(threadId) =>
          db.readOnlyReplica { implicit session =>
            Json.obj("threads" -> 1, "threadId" -> threadRepo.get(threadId).externalId)
          }
        case threadIds =>
          Json.obj("threads" -> threadIds.size)
      }.getOrElse(Json.obj()))
    }
  }
}

