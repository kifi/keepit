package com.keepit.controllers.tracking

import com.keepit.common.controller._
import com.keepit.common.time._
import com.keepit.heimdal._
import com.keepit.common.akka.SafeFuture

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.Inject
import play.api.libs.json.JsObject

class EventProxyController @Inject() (
    val userActionsHelper: UserActionsHelper,
    clock: Clock,
    heimdal: HeimdalServiceClient,
    heimdalContextBuilderFactoryBean: HeimdalContextBuilderFactory) extends UserActions with ShoeboxServiceController {

  def track() = MaybeUserAction(parse.tolerantJson) { request =>
    SafeFuture("event proxy") {
      val sentAt = clock.now()
      request.body.as[Seq[JsObject]].foreach { rawEvent =>
        val eventType = getEventType(rawEvent)
        val eventContext = (rawEvent \ "properties").as[HeimdalContext]
        val builder = heimdalContextBuilderFactoryBean.withRequestInfo(request)
        builder.addExistingContext(eventContext)
        val fullContext = builder.build
        val event = request.userIdOpt match {
          case Some(userId) => {
            val userEvent = UserEvent(userId, fullContext, eventType, sentAt)
            optionallySendUserUsedKifiEvent(userEvent)
            userEvent
          }
          case None => VisitorEvent(fullContext, eventType, sentAt)
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
  private def getEventType(rawEvent: JsObject): EventType = {
    val rawEventType = (rawEvent \ "event").as[String]
    val cleanEventType = HeimdalEventCompanion.all.map(_.typeCode).find(rawEventType.startsWith) match {
      case None => rawEventType
      case Some(typeCode) => rawEventType.stripPrefix(typeCode + "_")
    }
    EventType(cleanEventType)
  }
}
