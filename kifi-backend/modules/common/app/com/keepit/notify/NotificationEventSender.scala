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
    // We know that after the groupBy, the type of requests is the same because they have the same key.
    // This cast also makes the compiler know
    grouped.toList.asInstanceOf[List[(DbViewKey[M, R], Seq[DbViewRequest[M, R]]) forSome { type M <: HasId[M]; type R}]]
    grouped.foldLeft(Future.successful(existingView))(genericFoldGrouped)
  }

  // Turns the type-parameterized fold into an existential fold. We know the relationship between the key and requests in
  // the foldGrouped, but the compiler isn't very helpful with polymorphic function values in the fold.
  val genericFoldGrouped = (foldGrouped _).asInstanceOf[(Future[DbView], (ExDbViewKey, Seq[ExDbViewRequest])) => Future[DbView]]

  def foldGrouped(viewFut: Future[DbView], keyAndReqs: (DbViewKey[M, R], Seq[DbViewRequest[M, R]]) forSome { type M <: HasId[M]; type R}): Future[DbView] =
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
