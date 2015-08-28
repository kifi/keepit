package com.keepit.notify

import com.google.inject.{Inject, Singleton}
import com.keepit.common.db.Id
import com.keepit.eliza.ElizaServiceClient
import com.keepit.notify.NotificationEventSender.EventWithInfo
import com.keepit.notify.info._
import com.keepit.notify.model.event.NotificationEvent

import play.api.libs.json._
import play.api.libs.functional.syntax._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class NotificationEventSender @Inject() (
  infoProcessing: NotificationInfoProcessing,
  elizaServiceClient: ElizaServiceClient,
  implicit val ec: ExecutionContext
) {

  def send[N <: NotificationEvent](event: N): Unit = {
    val infoFut = infoProcessing.process(event.kind.info(Set(event)))
    infoFut.map { info =>
      elizaServiceClient.sendNotificationEvent(EventWithInfo(event, Some(info)))
    }
  }

}

object NotificationEventSender {

  case class EventWithInfo(event: NotificationEvent, info: Option[NotificationInfo])

  object EventWithInfo {

    implicit val format = (
      (__ \ "event").format[NotificationEvent] and
      (__ \ "info").formatNullable[NotificationInfo]
    )(EventWithInfo.apply, unlift(EventWithInfo.unapply))

  }

}
