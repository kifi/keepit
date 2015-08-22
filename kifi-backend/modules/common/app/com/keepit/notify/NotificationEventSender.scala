package com.keepit.notify

import com.google.inject.{Inject, Singleton}
import com.keepit.eliza.ElizaServiceClient
import com.keepit.notify.NotificationEventSender.EventWithInfo
import com.keepit.notify.info._
import com.keepit.notify.model.event.NotificationEvent

import play.api.libs.json._
import play.api.libs.functional.syntax._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class NotificationEventSender @Inject() (
  requestHandler: DbViewRequestHandler,
  elizaServiceClient: ElizaServiceClient,
  implicit val ec: ExecutionContext
) {

  def send[N <: NotificationEvent](wrappedEvent: ExistingDbView[N]): Unit = {
    val event = wrappedEvent.result
    val infoFut = process(event.kind.info(Set(event)), Some(wrappedEvent))
    infoFut.map { info =>
      elizaServiceClient.sendNotificationEvent(EventWithInfo(event, Some(info)))
    }
  }

  def send[N <: NotificationEvent](event: N): Unit = send(ExistingDbView()(event))

  def process[N <: NotificationEvent](usingView: UsingDbView[NotificationInfo], existing: Option[ExistingDbView[N]] = None): Future[NotificationInfo] = {
    val info = getView(usingView.requests, existing)
    info.map { view =>
      usingView.fn(view)
    }
  }

  def getView(requests: Seq[DbViewRequest[M, T] forSome { type M <: HasId[M]; type T}], existing: Option[ExistingDbView[_]]): Future[DbView] = {
    val existingView = existing.fold(DbView()) { existingModels =>
      existingModels.buildDbView
    }

    requests.foldLeft(Future.successful(existingView)) { (viewFut, request) =>
      viewFut.flatMap { view =>
        if (request.contained(view)) Future.successful(view)
        else processRequests(Seq(request), view) // todo need to batch requests
      }
    }
  }

  def processRequests[M <: HasId[M], R](requests: Seq[DbViewRequest[M, R]], baseView: DbView): Future[DbView] =
    for {
      results <- requestHandler(requests)
    } yield {
      requests.zip(results).foldLeft(baseView) {
        case (view, (request, result)) => view.update(request.key, request.id, result)
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
