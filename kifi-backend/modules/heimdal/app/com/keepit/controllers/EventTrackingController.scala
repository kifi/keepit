package com.keepit.controllers

import com.google.inject.Inject
import com.keepit.commander.HelpRankEventTrackingCommander
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.model.tracking.LibraryViewTrackingCommander
import com.keepit.shoebox.ShoeboxServiceClient
import com.kifi.franz.SQSQueue
import play.api.libs.json.{ JsArray, JsValue }
import com.keepit.common.core._

import scala.concurrent.{ Future, ExecutionContext }
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

trait UserEventHandler {
  def handleUserEvent(event: UserEvent): Unit
}

trait VisitorEventHandler {
  def handleVisitorEvent(event: VisitorEvent): Unit
}

class EventTrackingController @Inject() (
    heimdalEventQueue: SQSQueue[Seq[HeimdalEvent]],
    eventTrackingCommander: HelpRankEventTrackingCommander,
    libraryViewTrackingCommander: LibraryViewTrackingCommander,
    shoeboxClient: ShoeboxServiceClient,
    mixpanelClient: MixpanelClient,
    airbrake: AirbrakeNotifier,
    eventContextHelper: EventContextHelper,
    slackTeamInfoCache: InternalSlackTeamInfoCache,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val defaultContext: ExecutionContext) extends HeimdalServiceController with Logging {

  private[controllers] def trackInternalEvent(eventJs: JsValue): Unit = trackInternalEvent(eventJs.as[HeimdalEvent])

  private val userEventHandlers: Seq[UserEventHandler] = Seq(eventTrackingCommander, libraryViewTrackingCommander)
  private val visitorEventHandlers: Seq[VisitorEventHandler] = Seq(libraryViewTrackingCommander)

  private def trackInternalEvent(event: HeimdalEvent): Unit = {
    event match {
      case userEvent: UserEvent => handleUserEvent(userEvent).map(e => clientTrackEvent(e))
      case visitorEvent: VisitorEvent => handleVisitorEvent(visitorEvent).map(e => clientTrackEvent(e))
      case nonUserEvent: NonUserEvent => handleNonUserEvent(nonUserEvent).map(e => clientTrackEvent(e))
      case systemEvent: SystemEvent => clientTrackEvent(systemEvent)
      case anonEvent: AnonymousEvent => clientTrackEvent(anonEvent)
    }
  }

  private def clientTrackEvent[E <: HeimdalEvent](event: E)(implicit heimdalEventCompanion: HeimdalEventCompanion[E]): Unit = {
    try {
      mixpanelClient.track(event)
    } catch {
      case t: Throwable => airbrake.notify(s"error tracking event $event to mixpanel", t)
    }
  }

  private def handleUserEvent(rawUserEvent: UserEvent) = {
    val augmentors = Seq(
      UserIdAugmentor,
      new UserAugmentor(shoeboxClient),
      new UserExperimentAugmentor(shoeboxClient),
      new ExtensionVersionAugmentor(shoeboxClient),
      new UserSegmentAugmentor(shoeboxClient),
      new UserValuesAugmentor(shoeboxClient),
      new UserKifiCampaignIdAugmentor(shoeboxClient),
      new UserOrgValuesAugmentor(eventContextHelper),
      new UserKeepViewedAugmentor(eventContextHelper),
      new UserDiscussionViewedAugmentor(eventContextHelper),
      new SlackInfoAugmentor(eventContextHelper, shoeboxClient, slackTeamInfoCache)
    )

    val userEvent = if (rawUserEvent.eventType.name.startsWith("user_")) rawUserEvent.copy(eventType = EventType(rawUserEvent.eventType.name.substring(5))) else rawUserEvent


    val userEventF = EventAugmentor.safelyAugmentContext(userEvent, augmentors: _*).map { ctx =>
      userEvent.copy(context = ctx)
    }

    userEventF.onSuccess {
      case augmentedUserEvent =>
        userEventHandlers.foreach { handler =>
          handler.handleUserEvent(augmentedUserEvent)
        }
    }
    userEventF
  }

  private def handleNonUserEvent(rawNonUserEvent: NonUserEvent) = {
    val augmentors = Seq(NonUserIdentifierAugmentor, new SlackInfoAugmentor(eventContextHelper, shoeboxClient, slackTeamInfoCache))

    val nonUserEventF = EventAugmentor.safelyAugmentContext(rawNonUserEvent, augmentors: _*).map { ctx =>
      rawNonUserEvent.copy(context = ctx)
    }
    nonUserEventF
  }

  private def handleVisitorEvent(event: VisitorEvent) = {
    SafeFuture {
      visitorEventHandlers.foreach { handler =>
        handler.handleVisitorEvent(event)
      }
    }
    Future.successful(event)
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
