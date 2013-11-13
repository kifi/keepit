package com.keepit.heimdal.controllers

import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.time._
import com.keepit.heimdal._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.akka.SlowRunningExecutionContext

import play.api.mvc.Action
import play.api.libs.json.JsValue

import com.google.inject.Inject
import play.api.libs.json.JsArray

class EventTrackingController @Inject() (userEventLoggingRepo: UserEventLoggingRepo, systemEventLoggingRepo: SystemEventLoggingRepo) extends HeimdalServiceController {

  private[controllers] def trackInternalEvent(eventJs: JsValue) = {
    eventJs.as[HeimdalEvent] match {
      case systemEvent: SystemEvent => systemEventLoggingRepo.persist(systemEvent)
      case userEvent: UserEvent => userEventLoggingRepo.persist(userEvent)
    }
  }

  def trackInternalEventAction = Action(parse.json) { request =>
    SafeFuture{
      trackInternalEvent(request.body)
    }(SlowRunningExecutionContext.ec)
    Status(ACCEPTED)
  }

  private[controllers] def trackInternalEvents(eventsJs: JsValue) = eventsJs.as[JsArray].value.map(trackInternalEvent)

  def trackInternalEventsAction = Action(parse.json) { request =>
    SafeFuture{
      trackInternalEvents(request.body)
    }(SlowRunningExecutionContext.ec)
    Status(ACCEPTED)
  }
}
