package com.keepit.controllers.website

import com.keepit.common.controller.WebsiteController

import play.api.Play.current
import play.api.mvc._
import play.api._
import com.keepit.common.controller.ActionAuthenticator

import com.google.inject.Inject

class OnboardingController @Inject() (
  actionAuthenticator: ActionAuthenticator)
    extends WebsiteController(actionAuthenticator) {

  def tos = Action { implicit request =>
    Ok(views.html.website.termsOfService())
  }
}
