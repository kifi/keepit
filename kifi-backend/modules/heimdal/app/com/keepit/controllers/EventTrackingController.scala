package com.keepit.controllers

import com.google.inject.Inject
import com.keepit.commander.HelpRankEventTrackingCommander
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.curator.RecommendationUserAction
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.model.tracking.LibraryViewTrackingCommander
import com.kifi.franz.SQSQueue
import play.api.libs.json.{ JsArray, JsValue }

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

trait UserEventHandler {
  def handleUserEvent(event: UserEvent): Unit
}

class EventTrackingController @Inject() (
    userEventLoggingRepo: UserEventLoggingRepo,
    systemEventLoggingRepo: SystemEventLoggingRepo,
    anonymousEventLoggingRepo: AnonymousEventLoggingRepo,
    visitorEventLoggingRepo: VisitorEventLoggingRepo,
    nonUserEventLoggingRepo: NonUserEventLoggingRepo,
    heimdalEventQueue: SQSQueue[Seq[HeimdalEvent]],
    eventTrackingCommander: HelpRankEventTrackingCommander,
    libraryViewTrackingCommander: LibraryViewTrackingCommander,
    airbrake: AirbrakeNotifier,
    implicit val defaultContext: ExecutionContext) extends HeimdalServiceController {

  private[controllers] def trackInternalEvent(eventJs: JsValue): Unit = trackInternalEvent(eventJs.as[HeimdalEvent])

  private val userEventHandlers: Seq[UserEventHandler] = Seq(eventTrackingCommander, libraryViewTrackingCommander)

  private def trackInternalEvent(event: HeimdalEvent): Unit = event match {
    case systemEvent: SystemEvent => systemEventLoggingRepo.persist(systemEvent)
    case userEvent: UserEvent => handleUserEvent(userEvent)
    case anonymousEvent: AnonymousEvent => anonymousEventLoggingRepo.persist(anonymousEvent)
    case visitorEvent: VisitorEvent => visitorEventLoggingRepo.persist(visitorEvent)
    case nonUserEvent: NonUserEvent => nonUserEventLoggingRepo.persist(nonUserEvent)
  }

  private def handleUserEvent(rawUserEvent: UserEvent) = {
    val userEvent = if (rawUserEvent.eventType.name.startsWith("user_")) rawUserEvent.copy(eventType = EventType(rawUserEvent.eventType.name.substring(5))) else rawUserEvent
    SafeFuture { userEventLoggingRepo.persist(userEvent) }
    userEventHandlers.foreach { handler =>
      SafeFuture { handler.handleUserEvent(userEvent) }
    }
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
