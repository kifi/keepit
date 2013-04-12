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
class OnboardingController @Inject() (db: Database,
  userRepo: UserRepo,
  userValueRepo: UserValueRepo,
  actionAuthenticator: ActionAuthenticator)
    extends WebsiteController(actionAuthenticator) {

  def tos = HtmlAction(true)(authenticatedAction = { request =>
    val userAgreedToTOS = db.readOnly(userValueRepo.getValue(request.user.id.get, "agreedToTOS")(_)).map(_.toBoolean).getOrElse(false)
    if(userAgreedToTOS) {
      Ok(views.html.website.onboarding.userLegalAgreement())
    } else {
      Redirect(com.keepit.controllers.website.routes.HomeController.home)
    }
  }, unauthenticatedAction = { request =>
    Redirect(routes.HomeController.home())
  })

  def signup = Action { implicit request =>
    Redirect(securesocial.controllers.routes.LoginPage.login)
  }
}
