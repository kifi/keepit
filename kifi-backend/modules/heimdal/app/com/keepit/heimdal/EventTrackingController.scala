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

class EventTrackingController @Inject() (
  userEventLoggingRepo: UserEventLoggingRepo,
  systemEventLoggingRepo: SystemEventLoggingRepo,
  anonymousEventLoggingRepo: AnonymousEventLoggingRepo) extends HeimdalServiceController {

  private[controllers] def trackInternalEvent(eventJs: JsValue) = {
    eventJs.as[HeimdalEvent] match {
      case systemEvent: SystemEvent => systemEventLoggingRepo.persist(systemEvent)
      case userEvent: UserEvent => userEventLoggingRepo.persist(userEvent)
      case anonymousEvent: AnonymousEvent => anonymousEventLoggingRepo.persist(anonymousEvent)
    }
  }

  def trackInternalEventAction = Action(parse.tolerantJson) { request =>
    SafeFuture{
      trackInternalEvent(request.body)
    }(SlowRunningExecutionContext.ec)
    Status(ACCEPTED)
  }

  private[controllers] def trackInternalEvents(eventsJs: JsValue) = eventsJs.as[JsArray].value.map(trackInternalEvent)

  val TenMB = 1024 * 1024 * 10

  def trackInternalEventsAction = Action(parse.json(maxLength = TenMB)) { request =>
    SafeFuture{
      trackInternalEvents(request.body)
    }(SlowRunningExecutionContext.ec)
    Status(ACCEPTED)
  }
}
