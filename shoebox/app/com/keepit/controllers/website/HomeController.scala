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

import com.google.inject.{Inject, Singleton}

@Singleton
class HomeController @Inject() (db: Database,
  userRepo: UserRepo)
    extends WebsiteController {

  def home = Action{ request =>
    Ok(views.html.website.onboarding.userRequestReceived())
  }

  def giveMeA200 = Action { request =>
    Ok("You got it!")
  }
}
