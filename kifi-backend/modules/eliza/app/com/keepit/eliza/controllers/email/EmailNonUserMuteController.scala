package com.keepit.eliza.controllers.email

import com.google.inject.Inject
import com.keepit.common.controller.{ActionAuthenticator, ElizaServiceController, WebsiteController}
import play.api.data.Form
import play.api.data.Forms._
import scala.util.Success
import play.api.mvc.Action
import com.keepit.common.crypto.{PublicIdConfiguration, ModelWithPublicId}
import com.keepit.eliza.model.NonUserThread
import com.keepit.common.db.slick.Database
import com.keepit.eliza.commanders.MessagingCommander

class EmailNonUserMuteController @Inject() (
  db: Database,
  actionAuthenticator: ActionAuthenticator,
  messagingCommander: MessagingCommander,
  implicit val publicIdConfig: PublicIdConfiguration
  ) extends WebsiteController(actionAuthenticator) with ElizaServiceController {

  def optOut(publicId: String) = Action { implicit request =>
    ModelWithPublicId.decode[NonUserThread](publicId) match {
      case Success(id) => {
        messagingCommander.getNonUserThreadOpt(id) map { (nonUserThread: NonUserThread) =>
          Ok(views.html.muteEmails(nonUserThread.participant.identifier, !nonUserThread.muted, flash.get("msg")))
        } getOrElse (BadRequest)
      }
      case _ => BadRequest // don't tell the user why the token is wrong
    }
  }

  val optOutForm = Form("enabled" -> optional(text))
  def optOutAction(publicId: String) = Action { implicit request =>
    ModelWithPublicId.decode[NonUserThread](publicId) match {
      case Success(id) => {
        optOutForm.bindFromRequest.fold(formWithErrors => BadRequest, { enabled =>
          enabled.getOrElse("false") match {
              case s if s == "true" => messagingCommander.unmuteThreadForNonUser(id)
              case s if s == "false" => messagingCommander.muteThreadForNonUser(id)
            }
            Redirect(routes.EmailNonUserMuteController.optOut(publicId)).flashing("msg" -> "Your preferences have been updated.")
          }
        )
      }
      case _ => BadRequest
    }
  }
}
