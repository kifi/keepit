package com.keepit.controllers.site

import com.keepit.common.controller.{WebsiteController, ActionAuthenticator}
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.controller.FortyTwoCookies.ImpersonateCookie
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.healthcheck.HealthcheckPlugin
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import play.api.libs.json.Json
import akka.actor.ActorSystem

import com.google.inject.Inject
import com.keepit.eliza.controllers.internal.MessagingController
import com.keepit.eliza.{NotificationRouter, MessageThreadRepo}


class ChatterController @Inject() (
  messagingController: MessagingController,
  actionAuthenticator: ActionAuthenticator,
  notificationRouter: NotificationRouter,
  amazonInstanceInfo: AmazonInstanceInfo,
  threadRepo: MessageThreadRepo,
  db: Database,
  protected val shoebox: ShoeboxServiceClient,
  protected val impersonateCookie: ImpersonateCookie,
  protected val actorSystem: ActorSystem,
  protected val clock: Clock,
  protected val healthcheckPlugin: HealthcheckPlugin
  )
  extends WebsiteController(actionAuthenticator) {

  def getChatter() = AuthenticatedJsonToJsonAction { request =>
    val url = (request.body \ "url").as[String]
    Async {
      messagingController.getChatter(request.user.id.get, Seq(url)).map { res =>
        Ok(res.headOption.map { case (url, msgs) =>
          if (msgs.size == 1) {
            db.readOnly { implicit session =>
              Json.obj("threads" -> 1, "threadId" -> threadRepo.get(msgs.head).externalId.id)
            }
          } else {
            Json.obj("threads" -> msgs.size)
          }
        }.getOrElse(Json.obj()))
      }
    }
  }

}



