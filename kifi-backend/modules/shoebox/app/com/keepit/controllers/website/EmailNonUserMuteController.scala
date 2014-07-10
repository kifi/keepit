package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.common.controller.{ ShoeboxServiceController, ActionAuthenticator, WebsiteController }
import play.api.data.Form
import play.api.data.Forms._
import play.api.mvc.Action
import com.keepit.common.db.slick.Database
import com.keepit.eliza.ElizaServiceClient
import scala.concurrent._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class EmailNonUserMuteController @Inject() (
    db: Database,
    actionAuthenticator: ActionAuthenticator,
    elizaServiceClient: ElizaServiceClient) extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def optOut(publicId: String) = Action.async { implicit request =>
    elizaServiceClient.getNonUserThreadMuteInfo(publicId).map { content =>
      content map {
        case (identifier, muted) =>
          Ok(views.html.email.muteEmails(identifier, muted, flash.get("msg")))
      } getOrElse (BadRequest)
    }
  }

  val optOutForm = Form("should-mute" -> optional(text))
  def optOutAction(publicId: String) = Action.async { implicit request =>
    optOutForm.bindFromRequest.fold(formWithErrors => Future.successful(BadRequest), { shouldMuteInput =>
      val shouldMute = shouldMuteInput.getOrElse("false") match {
        case s if s == "true" => true
        case s if s == "false" => false
      }
      elizaServiceClient.setNonUserThreadMuteState(publicId, shouldMute) map { modified =>
        if (modified) Redirect(routes.EmailNonUserMuteController.optOut(publicId)).flashing("msg" -> "Your preferences have been updated.")
        else BadRequest // don't tell the user why the request failed
      }
    })
  }
}
