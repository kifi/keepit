package com.keepit.controllers.website

import com.keepit.common.controller.WebsiteController
import play.api.Play.current
import play.api.mvc._
import com.keepit.model._
import com.keepit.common.db.slick._
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.crypto.SimpleDESCrypt
import com.google.inject.Inject
import com.keepit.common.mail.{PostOffice, GenericEmailAddress, EmailAddressHolder}
import scala.util.Try
import play.api.data.Form
import play.api.data.Forms.{tuple, text, checked, optional}
import play.api.libs.json.Json

class EmailOptOutController @Inject() (
  db: Database,
  actionAuthenticator: ActionAuthenticator,
  emailOptOutRepo: EmailOptOutRepo)
  extends WebsiteController(actionAuthenticator) {

  def optOut(optOutToken: String) = Action {
    val email = getEmailFromOptOutToken(optOutToken)
    Ok//(views.website.optOutEmails())
  }

  val optOutForm = Form(
    tuple(
      "all" -> optional(text),
      "invite" -> optional(text),
      "message" -> optional(text)
    )
  )
  def optOutAction(optOutToken: String) = Action { implicit request =>
    val email = getEmailFromOptOutToken(optOutToken)

    email.map { emailAddress =>
      optOutForm.bindFromRequest.fold(
      formWithErrors => BadRequest,
      { case (all, invite, message) =>
        // Checkbox unchecked == unsubscribe
        db.readWrite { implicit session =>
          all.map { _ => emailOptOutRepo.optIn(emailAddress, PostOffice.Categories.ALL) }
            .getOrElse { emailOptOutRepo.optOut(emailAddress, PostOffice.Categories.ALL) }
          invite.map { _ => emailOptOutRepo.optIn(emailAddress, PostOffice.Categories.User.INVITATION) }
            .getOrElse { emailOptOutRepo.optOut(emailAddress, PostOffice.Categories.User.INVITATION) }
          message.map { _ => emailOptOutRepo.optIn(emailAddress, PostOffice.Categories.User.MESSAGE) }
            .getOrElse { emailOptOutRepo.optOut(emailAddress, PostOffice.Categories.User.MESSAGE) }
        }
        Ok // TODO: now what? "Email preferences saved"?
      })
    }.getOrElse(BadRequest)
  }

  // for development only
  def getToken(email: String) = Action {
    Ok(generateOptOutToken(GenericEmailAddress(email)))
  }

  private val crypt = new SimpleDESCrypt
  private val key = crypt.stringToKey(current.configuration.getString("optout.secret").get)

  def generateOptOutToken(emailAddress: EmailAddressHolder) = {
    crypt.crypt(key, emailAddress.address)
  }
  def getEmailFromOptOutToken(optOutToken: String): Try[EmailAddressHolder] = {
    crypt.decrypt(key, optOutToken).map(GenericEmailAddress(_))
  }
}
