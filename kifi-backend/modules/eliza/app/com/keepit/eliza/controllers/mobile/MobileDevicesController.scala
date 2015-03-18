package com.keepit.eliza.controllers.mobile

import com.google.inject.Inject
import com.keepit.realtime.{ DeviceType, UrbanAirship }
import com.keepit.common.controller.{ UserActions, UserActionsHelper, ElizaServiceController }
import com.keepit.common.logging.Logging
import play.api.libs.json._

class MobileDevicesController @Inject() (urbanAirship: UrbanAirship, val userActionsHelper: UserActionsHelper) extends UserActions with ElizaServiceController with Logging {

  def registerDevice(deviceType: String) = UserAction(parse.tolerantJson) { implicit request =>
    val jsonBody = request.body
    val tokenOpt = (jsonBody \ "token").asOpt[String]

    tokenOpt map { token =>
      val isDev: Boolean = (jsonBody \ "dev").asOpt[Boolean].exists(x => x)
      val signatureOpt = (jsonBody \ "deviceId").asOpt[String] // send a "signature" (coming in as deviceId)
      val device = urbanAirship.registerDevice(request.userId, token, DeviceType(deviceType), isDev, signatureOpt)
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
