package com.keepit.controllers.site

import com.keepit.common.controller.{ ElizaServiceController, WebsiteController, ActionAuthenticator }
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
    actionAuthenticator: ActionAuthenticator,
    threadRepo: MessageThreadRepo,
    db: Database) extends WebsiteController(actionAuthenticator) with ElizaServiceController {

  def getChatter() = JsonAction.authenticatedParseJsonAsync { request =>
    val url = (request.body \ "url").as[String]
    messagingCommander.getChatter(request.user.id.get, Seq(url)).map { res =>
      Ok(res.headOption.map {
        case (url, msgs) =>
          if (msgs.size == 1) {
            db.readOnlyReplica { implicit session =>
              Json.obj("threads" -> 1, "threadId" -> threadRepo.get(msgs.head).externalId.id)
            }
          } else {
            Json.obj("threads" -> msgs.size)
          }
      }.getOrElse(Json.obj()))
    }
  }
}

