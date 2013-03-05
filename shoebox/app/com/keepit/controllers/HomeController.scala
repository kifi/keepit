package com.keepit.controllers

import com.keepit.common.controller.FortyTwoController
import com.keepit.common.logging.Logging

import play.api.Play.current
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.http.ContentTypes
import play.api.mvc._
import play.api._
import com.keepit.inject._
import com.keepit.model._
import com.keepit.common.db.slick._

class HomeController extends FortyTwoController {

  def home = Action{ request =>
    log.info("yet another homepage access!")
    val html = io.Source.fromURL(Play.resource("/public/html/index.html").get).mkString
    Ok(html).as(ContentTypes.HTML)
  }

  def giveMeA200 = Action { request =>
    Ok("You got it!")
  }

  def upgrade = AuthenticatedHtmlAction { implicit request =>
    val user = inject[Database].readOnly { implicit s => inject[UserRepo].get(request.userId) }
    log.info("Looks like %s needs to upgrade".format(user.firstName + " " + user.lastName))
    val html = io.Source.fromURL(Play.resource("/public/html/upgrade.html").get).mkString
    Ok(html).as(ContentTypes.HTML)
  }

}
