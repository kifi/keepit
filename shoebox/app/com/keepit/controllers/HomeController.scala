package com.keepit.controllers

import play.api.data._
import play.api._
import play.api.Play.current
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.ws.WS
import play.api.mvc._
import play.api.http.ContentTypes
import com.keepit.controllers.CommonActions._
import com.keepit.common.db.ExternalId
import com.keepit.common.logging.Logging
import com.keepit.common.controller.FortyTwoController

object HomeController extends FortyTwoController {

  def home = Action{ request =>
    log.info("yet another homepage access!")
    val html = io.Source.fromURL(Play.resource("/public/html/index.html").get).mkString
    Ok(html).as(ContentTypes.HTML)
  }

}
