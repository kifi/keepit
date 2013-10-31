package com.keepit.heimdal.controllers

import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.time._
import com.keepit.heimdal.{UserEventLoggingRepo, UserEvent}
import com.keepit.common.akka.SafeFuture
import com.keepit.common.akka.SlowRunningExecutionContext

import play.api.http.Status.ACCEPTED
import play.api.mvc.Action
import play.api.libs.json.{JsValue, JsArray}

import com.google.inject.Inject

class EventTrackingController @Inject() (userEventLoggingRepo: UserEventLoggingRepo) extends HeimdalServiceController {

  private[controllers] def trackInternalEvent(eventJs: JsValue) = {
    val event: UserEvent = eventJs.as[UserEvent]
    userEventLoggingRepo.insert(event)
  }

  def trackInternalEventAction = Action(parse.json) { request =>
    SafeFuture{
      trackInternalEvent(request.body)
    }(SlowRunningExecutionContext.ec)
    Status(ACCEPTED)
  }

  private[controllers] def trackInternalEvents(eventsJs: JsValue) = {
    eventsJs.as[JsArray].value.map(js => js.as[UserEvent]).map{e => userEventLoggingRepo.insert(e)}
  }

  def trackInternalEventsAction = Action(parse.json) { request =>
    SafeFuture{
      trackInternalEvents(request.body)
    }(SlowRunningExecutionContext.ec)
    Status(ACCEPTED)
  }

}
