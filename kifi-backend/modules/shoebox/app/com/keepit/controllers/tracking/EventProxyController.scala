package com.keepit.controllers.tracking

import com.keepit.commanders.UserIpAddressCommander
import com.keepit.common.controller._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.heimdal._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.core._

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.Inject
import play.api.libs.json.JsObject

class EventProxyController @Inject() (
    val userActionsHelper: UserActionsHelper,
    clock: Clock,
    userIpAddressCommander: UserIpAddressCommander,
    heimdal: HeimdalServiceClient,
    heimdalContextBuilderFactoryBean: HeimdalContextBuilderFactory,
    airbrake: AirbrakeNotifier) extends UserActions with ShoeboxServiceController {

  def track() = MaybeUserAction(parse.tolerantJson) { request =>
    request match {
      case req: UserRequest[_] => userIpAddressCommander.logUserByRequest(req)
      case _ =>
    }
    import com.keepit.common.core._
    SafeFuture("event proxy") {
      val sentAt = clock.now()
      request.body.as[Seq[JsObject]].foreach { rawEvent =>
        val rawEventType = (rawEvent \ "event").as[String]
        val (eventType, intendedEventOpt) = getEventType(rawEventType)
        val builder = heimdalContextBuilderFactoryBean.withRequestInfo(request)
        (rawEvent \ "properties").as[JsObject].nonNullFields.asOpt[HeimdalContext] match {
          case Some(ctx) =>
            builder.addExistingContext(ctx)
          case None =>
            airbrake.notify(s"[EventProxyController] Can't parse event: $rawEvent")
        }

        val fullContext = builder.build
        val event = (request, intendedEventOpt) match {
          case (_, Some(VisitorEvent)) => VisitorEvent(fullContext, eventType, sentAt)
          case (userRequest: UserRequest[_], _) => UserEvent(userRequest.userId, fullContext, eventType, sentAt) tap optionallySendUserUsedKifiEvent
          case _ => VisitorEvent(fullContext, eventType, sentAt)
        }
        heimdal.trackEvent(event)
      }
    }
    NoContent
  }

  // integrate some events into used_kifi events as actions
  private def optionallySendUserUsedKifiEvent(event: UserEvent): Unit = {
    val validEvents = Set(UserEventTypes.VIEWED_PAGE, UserEventTypes.VIEWED_PANE)
    if (validEvents.contains(event.eventType)) {
      val builder = heimdalContextBuilderFactoryBean()
      builder.addExistingContext(event.context)
      val action = event.eventType match {
        case UserEventTypes.VIEWED_PAGE => "viewedSite"
        case UserEventTypes.VIEWED_PANE => "viewedPane"
      }
      builder += ("action", action)
      heimdal.trackEvent(UserEvent(event.userId, builder.build, UserEventTypes.USED_KIFI, event.time))
    }
  }

  // Events are coming in from clients with the "user_" or "visitor_" prefix already present, stripping it here since it's automatically prepended in MixpanelClient.
  private def getEventType(rawEventType: String): (EventType, Option[HeimdalEventCompanion[_ <: HeimdalEvent]]) = {
    val companionOpt = HeimdalEventCompanion.all.find(companion => rawEventType.startsWith(companion.typeCode))
    val eventType = EventType(companionOpt.map(companion => rawEventType.stripPrefix(companion.typeCode + "_")) getOrElse rawEventType)
    (eventType, companionOpt)
  }
}
