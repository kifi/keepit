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

  def getView(requests: Seq[ExDbViewRequest], existing: Option[ExistingDbView[_]]): Future[DbView] = {
    val existingView = existing.fold(DbView()) { existingModels =>
      existingModels.buildDbView
    }

    val grouped = requests.groupBy(_.key)
    grouped.toList.asInstanceOf[List[(DbViewKey[M, R], Seq[DbViewRequest[M, R]]) forSome { type M <: HasId[M]; type R}]]
    grouped.foldLeft(Future.successful(existingView))(genericFoldGrouped)
  }

  val genericFoldGrouped = (foldGrouped _).asInstanceOf[(Future[DbView], (ExDbViewKey, Seq[ExDbViewRequest])) => Future[DbView]]

  def foldGrouped[M <: HasId[M], R](viewFut: Future[DbView], keyAndReqs: (DbViewKey[M, R], Seq[DbViewRequest[M, R]])): Future[DbView] =
    keyAndReqs match {
      case (key, reqs) =>  viewFut.flatMap { view =>
        val toRequest = reqs.filter(req => !view.contains(key, req.id))
        requestHandler(key, reqs).map { keyMap =>
          keyMap.toList.foldLeft(view) {
            case (viewAcc, (id, result)) => viewAcc.update(key, id, result)
          }
        }
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
