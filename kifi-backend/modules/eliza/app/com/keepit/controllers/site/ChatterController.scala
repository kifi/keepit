package com.keepit.controllers.site

import com.keepit.common.controller.{WebsiteController, ActionAuthenticator}
import com.keepit.shoebox.{ShoeboxServiceClient}
import com.keepit.common.controller.FortyTwoCookies.ImpersonateCookie
import com.keepit.common.time._
import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.healthcheck.{HealthcheckPlugin}
import scala.concurrent.ExecutionContext.Implicits.global

import play.api.libs.json.Json
import akka.actor.ActorSystem

import com.google.inject.Inject
import com.keepit.eliza.{NotificationRouter, MessagingController}


class ChatterController @Inject() (
  messagingController: MessagingController,
  actionAuthenticator: ActionAuthenticator,
  notificationRouter: NotificationRouter,
  amazonInstanceInfo: AmazonInstanceInfo,
  protected val shoebox: ShoeboxServiceClient,
  protected val impersonateCookie: ImpersonateCookie,
  protected val actorSystem: ActorSystem,
  protected val clock: Clock,
  protected val healthcheckPlugin: HealthcheckPlugin
  )
  extends WebsiteController(actionAuthenticator) {

  def getChatter() = AuthenticatedJsonToJsonAction { request =>
    val urls = request.body.as[Seq[String]]
    Async {
      messagingController.getChatter(request.user.id.get, urls).map { res =>
        Ok(res.map { case (url, msgCount) =>
          Json.obj("comments" -> 0, "conversations" -> msgCount)
        })
      }
    }
  }

}



