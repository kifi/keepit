package com.keepit.controllers.website

import com.keepit.common.controller.{ShoeboxServiceController, WebsiteController, ActionAuthenticator}
import play.api.mvc._
import com.keepit.model._
import com.keepit.common.db.slick._
import com.google.inject.Inject
import com.keepit.common.mail.EmailAddress
import scala.util.{Success, Failure}
import play.api.data.Form
import play.api.data.Forms.{tuple, text, optional}
import com.keepit.social.SecureSocialClientIds
import com.keepit.commanders.emails.EmailOptOutCommander

class EmailOptOutController @Inject() (
  db: Database,
  actionAuthenticator: ActionAuthenticator,
  emailOptOutRepo: EmailOptOutRepo,
  commander: EmailOptOutCommander,
  secureSocialClientIds: SecureSocialClientIds)
  extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def optOut(optOutToken: String) = Action { implicit request =>
    val email = commander.getEmailFromOptOutToken(optOutToken)

    email match {
      case Success(addr) =>
        val opts = db.readOnly { implicit session =>
          emailOptOutRepo.getByEmailAddress(addr).collect { case c => NotificationCategory(c.category.category) }
        }

        Ok(views.html.website.optOutEmails(addr.address, opts, flash.get("msg"), secureSocialClientIds))
      case _ => BadRequest // Don't tell the user why the token is wrong
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
            all.collect { case s if s == "true" => emailOptOutRepo.optIn(emailAddress, NotificationCategory.ALL) }
              .getOrElse { emailOptOutRepo.optOut(emailAddress, NotificationCategory.ALL) }
            invite.map { _ => emailOptOutRepo.optIn(emailAddress, NotificationCategory.NonUser.INVITATION) }
              .getOrElse { emailOptOutRepo.optOut(emailAddress, NotificationCategory.NonUser.INVITATION) }
            message.map { _ => emailOptOutRepo.optIn(emailAddress, NotificationCategory.User.MESSAGE) }
              .getOrElse { emailOptOutRepo.optOut(emailAddress, NotificationCategory.User.MESSAGE) }
          }
          Redirect(routes.EmailOptOutController.optOut(optOutToken)).flashing("msg" -> "Your preferences have been updated.")
        }
      )
    }.getOrElse(BadRequest)
  }

  def getUnsubscribeUrlForEmail(email: String) = Action { implicit request =>
    Ok(s"https://www.kifi.com${com.keepit.controllers.website.routes.EmailOptOutController.optOut(commander.generateOptOutToken(EmailAddress(email)))}")
  }

  // for development only
  def getToken(email: String) = Action {
    Ok(commander.generateOptOutToken(EmailAddress(email)))
  }


}
