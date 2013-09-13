package com.keepit.heimdal

import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.time._

import play.api.mvc.Action
import play.api.libs.json.{JsValue}

import com.google.inject.Inject

//remember user block!!

class EventTrackingController @Inject() (userEventLoggingRepo: UserEventLoggingRepo) extends HeimdalServiceController {


  def trackInternalEvent = Action(parse.json) { request =>
    val event: UserEvent = request.body.as[UserEvent]
    userEventLoggingRepo.insert(event)
    Ok("")
  }


}
