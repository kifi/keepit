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

import com.google.inject.{Inject, Singleton}

@Singleton
class HomeController @Inject() (db: Database,
  userRepo: UserRepo,
  userValueRepo: UserValueRepo,
  actionAuthenticator: ActionAuthenticator)
    extends WebsiteController(actionAuthenticator) {

  def home = HtmlAction(true)(authenticatedAction = { implicit request =>
    if(request.user.state == UserStates.PENDING) {
      Ok(views.html.website.onboarding.userRequestReceived())
    }
    else {
      val userAgreedToTOS = db.readOnly(userValueRepo.getValue(request.user.id.get, "agreedToTOS")(_)).map(_.toBoolean).getOrElse(false)
      if(!userAgreedToTOS) {
        Redirect(routes.OnboardingController.tos())
      }
      Ok(views.html.website.userHome())
    }
  }, unauthenticatedAction = { implicit request =>
    Ok(views.html.website.welcome())
  })

  def invite = AuthenticatedHtmlAction { implicit request =>
    Ok(views.html.website.inviteFriends())
  }

  def gettingStarted = AuthenticatedHtmlAction { implicit request =>
    Ok(views.html.website.gettingStarted())
  }


  def giveMeA200 = Action { request =>
    Ok("You got it!")
  }
}
