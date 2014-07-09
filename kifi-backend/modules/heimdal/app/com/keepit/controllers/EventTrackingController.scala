package com.keepit.controllers

import com.google.inject.Inject
import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.heimdal._
import com.keepit.model.{ AnonymousEventLoggingRepo, UserEventLoggingRepo, SystemEventLoggingRepo, NonUserEventLoggingRepo }
import com.kifi.franz.SQSQueue
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsArray, JsValue }

import scala.concurrent.duration._
import scala.util.{ Failure, Success }

class EventTrackingController @Inject() (
    userEventLoggingRepo: UserEventLoggingRepo,
    systemEventLoggingRepo: SystemEventLoggingRepo,
    anonymousEventLoggingRepo: AnonymousEventLoggingRepo,
    nonUserEventLoggingRepo: NonUserEventLoggingRepo,
    heimdalEventQueue: SQSQueue[Seq[HeimdalEvent]],
    airbrake: AirbrakeNotifier) extends HeimdalServiceController {

  private[controllers] def trackInternalEvent(eventJs: JsValue): Unit = trackInternalEvent(eventJs.as[HeimdalEvent])

  private def trackInternalEvent(event: HeimdalEvent): Unit = event match {
    case systemEvent: SystemEvent => systemEventLoggingRepo.persist(systemEvent)
    case userEvent: UserEvent => userEventLoggingRepo.persist(userEvent)
    case anonymousEvent: AnonymousEvent => anonymousEventLoggingRepo.persist(anonymousEvent)
    case nonUserEvent: NonUserEvent => nonUserEventLoggingRepo.persist(nonUserEvent)
  }

  def readIncomingEvent(): Unit = {
    heimdalEventQueue.nextWithLock(1 minute).onComplete {
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

  private[controllers] def trackInternalEvents(eventsJs: JsValue) = eventsJs.as[JsArray].value.map(trackInternalEvent)
}
