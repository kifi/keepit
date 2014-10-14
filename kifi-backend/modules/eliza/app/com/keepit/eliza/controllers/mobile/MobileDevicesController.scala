package com.keepit.eliza.controllers.mobile

import com.google.inject.Inject
import com.keepit.realtime.{ DeviceType, UrbanAirship }
import com.keepit.common.controller.{ UserActions, UserActionsHelper, ElizaServiceController, WebsiteController }
import com.keepit.common.logging.Logging
import play.api.libs.json._

class MobileDevicesController @Inject() (urbanAirship: UrbanAirship, val userActionsHelper: UserActionsHelper) extends UserActions with ElizaServiceController with Logging {

  def registerDevice(deviceType: String) = UserAction(parse.tolerantJson) { implicit request =>
    (request.body \ "token").asOpt[String] map { token =>
      val isDev: Boolean = (request.body \ "dev").asOpt[Boolean].exists(x => x)
      val device = urbanAirship.registerDevice(request.userId, token, DeviceType(deviceType), isDev)
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
