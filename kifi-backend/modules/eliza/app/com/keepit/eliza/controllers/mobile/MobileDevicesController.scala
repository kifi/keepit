package com.keepit.eliza.controllers.mobile

import com.google.inject.Inject
import com.keepit.realtime.{ DeviceType, UrbanAirship }
import com.keepit.common.controller.{ ActionAuthenticator, ElizaServiceController, WebsiteController }
import com.keepit.common.logging.Logging
import play.api.libs.json._

class MobileDevicesController @Inject() (urbanAirship: UrbanAirship, actionAuthenticator: ActionAuthenticator) extends WebsiteController(actionAuthenticator) with ElizaServiceController with Logging {

  def registerDevice(deviceType: String) = JsonAction.authenticatedParseJson { implicit request =>
    (request.body \ "token").asOpt[String] map { token =>
      val device = urbanAirship.registerDevice(request.userId, token, DeviceType(deviceType))
      Ok(Json.obj(
        "token" -> device.token
      ))
    } getOrElse {
      BadRequest(Json.obj(
        "error" -> "Body must contain a token parameter"
      ))
    }
  }

}
