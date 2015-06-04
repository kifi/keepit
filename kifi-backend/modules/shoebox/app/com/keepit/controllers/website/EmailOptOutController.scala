package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders.emails.EmailOptOutCommander
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.db.slick._
import com.keepit.common.mail.EmailAddress
import com.keepit.model._
import com.keepit.social.SecureSocialClientIds
import play.api.data.Form
import play.api.data.Forms.{ optional, text, tuple }
import play.api.mvc._

import scala.util.Success

class EmailOptOutController @Inject() (
  db: Database,
  val userActionsHelper: UserActionsHelper,
  emailOptOutRepo: EmailOptOutRepo,
  commander: EmailOptOutCommander,
  secureSocialClientIds: SecureSocialClientIds)
    extends UserActions with ShoeboxServiceController {

  def optOut(optOutToken: String) = Action { implicit request =>
    val email = commander.getEmailFromOptOutToken(optOutToken)

    email match {
      case Success(addr) =>
        // Reading from master since the value was likely just written
        val opts = db.readOnlyMaster { implicit session =>
          emailOptOutRepo.getByEmailAddress(addr).collect { case c => NotificationCategory(c.category.category) }
        }

        Ok(views.html.website.optOutEmails(addr.address, opts, request.flash.get("msg"), secureSocialClientIds))
      case _ => BadRequest // Don't tell the user why the token is wrong
    }

  }

  val optOutForm = Form(
    tuple(
      "all" -> optional(text),
      "invite" -> optional(text),
      "message" -> optional(text),
      "digest" -> optional(text),
      "activity" -> optional(text),
      "gratification" -> optional(text)
    )
  )
  def optOutAction(optOutToken: String) = Action { implicit request =>
    val email = commander.getEmailFromOptOutToken(optOutToken)

    email.map { emailAddress =>
      optOutForm.bindFromRequest.fold(
        formWithErrors => BadRequest,
        {
          case (all, invite, message, digest, activity, gratification) =>
            // Checkbox unchecked == unsubscribe
            db.readWrite { implicit session =>
              all.collect { case s if s == "true" => emailOptOutRepo.optIn(emailAddress, NotificationCategory.ALL) }
                .getOrElse { emailOptOutRepo.optOut(emailAddress, NotificationCategory.ALL) }
              invite.map { _ => emailOptOutRepo.optIn(emailAddress, NotificationCategory.NonUser.INVITATION) }
                .getOrElse { emailOptOutRepo.optOut(emailAddress, NotificationCategory.NonUser.INVITATION) }
              message.map { _ => emailOptOutRepo.optIn(emailAddress, NotificationCategory.User.MESSAGE) }
                .getOrElse { emailOptOutRepo.optOut(emailAddress, NotificationCategory.User.MESSAGE) }
              digest.map { _ => emailOptOutRepo.optIn(emailAddress, NotificationCategory.User.DIGEST) }
                .getOrElse { emailOptOutRepo.optOut(emailAddress, NotificationCategory.User.DIGEST) }
              activity.map { _ => emailOptOutRepo.optIn(emailAddress, NotificationCategory.User.ACTIVITY) }
                .getOrElse { emailOptOutRepo.optOut(emailAddress, NotificationCategory.User.ACTIVITY) }
              gratification.map { _ => emailOptOutRepo.optIn(emailAddress, NotificationCategory.User.GRATIFICATION_EMAIL) }
                .getOrElse { emailOptOutRepo.optOut(emailAddress, NotificationCategory.User.GRATIFICATION_EMAIL) }
            }
            Redirect(routes.EmailOptOutController.optOut(optOutToken)).flashing("msg" -> "Your preferences have been updated.")
        }
      )
    }.getOrElse(BadRequest)
  }

  def getUnsubscribeUrlForEmail(email: EmailAddress) = Action { implicit request =>
    Ok(s"https://www.kifi.com${com.keepit.controllers.website.routes.EmailOptOutController.optOut(commander.generateOptOutToken(email))}")
  }

  // for development only
  def getToken(email: EmailAddress) = Action {
    Ok(commander.generateOptOutToken(email))
  }

}
