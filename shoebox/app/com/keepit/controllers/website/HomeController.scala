package com.keepit.controllers.website

import com.keepit.common.controller.WebsiteExtensionController
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
    extends WebsiteExtensionController {

  def home = Action{ request =>
    log.info("yet another homepage access!")
    val html = io.Source.fromURL(Play.resource("/public/html/index.html").get).mkString
    Ok(html).as(ContentTypes.HTML)
  }

  def giveMeA200 = Action { request =>
    Ok("You got it!")
  }

  def upgrade = AuthenticatedHtmlAction { implicit request =>
    val user = db.readOnly { implicit s => userRepo.get(request.userId) }
    log.info("Looks like %s needs to upgrade".format(user.firstName + " " + user.lastName))
    val html = io.Source.fromURL(Play.resource("/public/html/upgrade.html").get).mkString
    Ok(html).as(ContentTypes.HTML)
  }

}
