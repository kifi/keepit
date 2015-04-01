package com.keepit.eliza.controllers.mobile

import com.google.inject.Inject
import com.keepit.realtime.{ MobilePushNotifier, DeviceType }
import com.keepit.common.controller.{ UserActions, UserActionsHelper, ElizaServiceController }
import com.keepit.common.logging.Logging
import play.api.libs.json._

class MobileDevicesController @Inject() (pushNotifier: MobilePushNotifier, val userActionsHelper: UserActionsHelper) extends UserActions with ElizaServiceController with Logging {

  def registerDevice(deviceType: String) = UserAction(parse.tolerantJson) { implicit request =>
    val jsonBody = request.body
    val tokenOpt = (jsonBody \ "token").asOpt[String]
    val isDev: Boolean = (jsonBody \ "dev").asOpt[Boolean].exists(x => x)
    val signatureOpt = (jsonBody \ "signature").asOpt[String].orElse((jsonBody \ "deviceId").asOpt[String]) // todo (aaron & mobile): right now iOS sends "deviceId", would be better & clear to make it "signature"

    pushNotifier.registerDevice(request.userId, tokenOpt, DeviceType(deviceType), isDev, signatureOpt) match {
      case Left(error) =>
        BadRequest(Json.obj("error" -> error))
      case Right(device) =>
        Ok(Json.obj("token" -> device.token))
    }
  }

}
