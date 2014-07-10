package com.keepit.controllers.internal

import com.keepit.common.time._
import com.google.inject.{ Provider, Inject }
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.commanders.PageCommander
import com.keepit.common.logging.Logging
import play.api.mvc.Action
import play.api.libs.json._

class ShoeboxPageController @Inject() (
  pageCommander: PageCommander)(implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices)
    extends ShoeboxServiceController with Logging {

  def isSensitiveURI() = Action(parse.tolerantJson) { request =>
    val uri = (request.body \ "uri").as[String]
    Ok(Json.toJson(pageCommander.isSensitiveURI(uri)))
  }
}
