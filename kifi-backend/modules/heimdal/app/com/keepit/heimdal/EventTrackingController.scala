package com.keepit.heimdal.controllers

import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.time._
import com.keepit.heimdal._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.akka.SlowRunningExecutionContext

import play.api.mvc.Action
import play.api.libs.json.JsValue

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.Inject
import play.api.libs.json.JsArray
import scala.util.{Success, Failure}
import scala.concurrent.duration._
import com.kifi.franz.SQSQueue
import com.keepit.common.healthcheck.AirbrakeNotifier

class EventTrackingController @Inject() (
  userEventLoggingRepo: UserEventLoggingRepo,
  systemEventLoggingRepo: SystemEventLoggingRepo,
  anonymousEventLoggingRepo: AnonymousEventLoggingRepo,
  nonUserEventLoggingRepo: NonUserEventLoggingRepo,
  heimdalEventQueue: SQSQueue[Seq[HeimdalEvent]],
  airbrake: AirbrakeNotifier
) extends HeimdalServiceController {

  private[controllers] def trackInternalEvent(eventJs: JsValue): Unit = trackInternalEvent(eventJs.as[HeimdalEvent])

  private def trackInternalEvent(event: HeimdalEvent): Unit = event match {
    case systemEvent: SystemEvent => systemEventLoggingRepo.persist(systemEvent)
    case userEvent: UserEvent => userEventLoggingRepo.persist(userEvent)
    case anonymousEvent: AnonymousEvent => anonymousEventLoggingRepo.persist(anonymousEvent)
    case nonUserEvent: NonUserEvent => nonUserEventLoggingRepo.persist(nonUserEvent)
  }

  def readIncomingEvent(): Unit = {
    heimdalEventQueue.nextWithLock(1 minute).onComplete{
      case Success(result) => {
        try {
          result.map { sqsMessage =>
            sqsMessage.consume { events =>
              events foreach trackInternalEvent
            }
          }
        } catch {
          case e: Throwable => log.warn(s"Failed to read event: ${e.getMessage}")
        } finally {
          readIncomingEvent()
        }
      }
      case Failure(t) => {
        airbrake.notify("Failed reading incoming messages from queue", t)
        readIncomingEvent()
      }
    }
  }

  @deprecated(message = "use queue new, remove then all clients are upgraded", since = "event queue was introduced")
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
