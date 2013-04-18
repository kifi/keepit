package com.keepit.controllers.website

import com.keepit.common.controller.WebsiteController
import com.keepit.common.logging.Logging

import play.api.Play.current
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.http.ContentTypes
import play.api.mvc._
import play.api._
import com.keepit.model._
import com.keepit.common.db.slick._
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.db.ExternalId

import com.google.inject.{Inject, Singleton}

@Singleton
class OnboardingController @Inject() (db: Database,
  userRepo: UserRepo,
  userValueRepo: UserValueRepo,
  socialConnectionRepo: SocialConnectionRepo,
  invitationRepo: InvitationRepo,
  actionAuthenticator: ActionAuthenticator)
    extends WebsiteController(actionAuthenticator) {

  def tos = HtmlAction(true)(authenticatedAction = { request =>
    val userAgreedToTOS = db.readOnly(userValueRepo.getValue(request.user.id.get, "agreedToTOS")(_)).map(_.toBoolean).getOrElse(false)
    if(!userAgreedToTOS) {
      val friendsOnKifi = db.readOnly { implicit session =>
        socialConnectionRepo.getFortyTwoUserConnections(request.user.id.get).map { u =>
          val user = userRepo.get(u)
          if(user.state == UserStates.ACTIVE) Some(user.externalId)
          else None
        } flatten
      }
      Ok(views.html.website.onboarding.userLegalAgreement(request.user, friendsOnKifi))
    } else {
      Redirect(com.keepit.controllers.website.routes.HomeController.home)
    }
  }, unauthenticatedAction = { request =>
    Redirect(routes.HomeController.home())
  })
  
  def tosAccept = HtmlAction(true)(authenticatedAction = { request =>
    db.readWrite(userValueRepo.setValue(request.user.id.get, "agreedToTOS", "true")(_))
    Redirect(com.keepit.controllers.website.routes.HomeController.gettingStarted)
  }, unauthenticatedAction = { request =>
    Redirect(routes.HomeController.home())
  })

  def signup(inviteId: String = "") = Action { implicit request =>
    if(inviteId != "") {
      val id = ExternalId[Invitation](inviteId)
      db.readWrite { implicit session =>
        invitationRepo.getOpt(id) match {
          case Some(invite) =>
            invitationRepo.save(invite.copy(state = InvitationStates.ACCEPTED))
          case None => 
        }
      }
    }
    Redirect(securesocial.controllers.routes.LoginPage.login)
  }
}
