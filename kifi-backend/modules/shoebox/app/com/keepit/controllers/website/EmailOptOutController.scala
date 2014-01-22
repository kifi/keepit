package com.keepit.controllers.website

import com.keepit.common.controller.WebsiteController
import play.api.Play.current
import play.api.mvc._
import com.keepit.model._
import com.keepit.common.db.slick._
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.crypto.SimpleDESCrypt
import com.keepit.commanders.EmailOptOutCommander
import com.google.inject.Inject
import com.keepit.common.mail.{PostOffice, GenericEmailAddress, EmailAddressHolder}
import scala.util.{Success, Try, Failure}
import play.api.data.Form
import play.api.data.Forms.{tuple, text, checked, optional}
import play.api.libs.json.Json
import com.keepit.common.KestrelCombinator

class EmailOptOutController @Inject() (
  db: Database,
  actionAuthenticator: ActionAuthenticator,
  emailOptOutRepo: EmailOptOutRepo,
  commander: EmailOptOutCommander)
  extends WebsiteController(actionAuthenticator) {


  def optOut(optOutToken: String) = Action { implicit request =>
    val email = commander.getEmailFromOptOutToken(optOutToken)

    email match {
      case Success(addr) =>
        val opts = db.readOnly { implicit session =>
          emailOptOutRepo.getByEmailAddress(addr).collect { case c => c.category }
        }

        Ok(views.html.website.optOutEmails(addr.address, opts, flash.get("msg")))
      case Failure(ex) => BadRequest(ex.toString)
    }

  }

  val optOutForm = Form(
    tuple(
      "all" -> optional(text),
      "invite" -> optional(text),
      "message" -> optional(text)
    )
  )
  def optOutAction(optOutToken: String) = Action { implicit request =>
    val email = commander.getEmailFromOptOutToken(optOutToken)

    email.map { emailAddress =>
      optOutForm.bindFromRequest.fold(
        formWithErrors => BadRequest,
        { case (all, invite, message) =>
          // Checkbox unchecked == unsubscribe
          db.readWrite { implicit session =>
            all.collect { case s if s == "true" => emailOptOutRepo.optIn(emailAddress, PostOffice.Categories.ALL) }
              .getOrElse { emailOptOutRepo.optOut(emailAddress, PostOffice.Categories.ALL) }
            invite.map { _ => emailOptOutRepo.optIn(emailAddress, PostOffice.Categories.User.INVITATION) }
              .getOrElse { emailOptOutRepo.optOut(emailAddress, PostOffice.Categories.User.INVITATION) }
            message.map { _ => emailOptOutRepo.optIn(emailAddress, PostOffice.Categories.User.MESSAGE) }
              .getOrElse { emailOptOutRepo.optOut(emailAddress, PostOffice.Categories.User.MESSAGE) }
          }
          Redirect(routes.EmailOptOutController.optOut(optOutToken)).flashing("msg" -> "Your preferences have been updated.")
        }
      )
    }.getOrElse(BadRequest)
  }

  // for development only
  def getToken(email: String) = Action {
    Ok(commander.generateOptOutToken(GenericEmailAddress(email)))
  }


}
