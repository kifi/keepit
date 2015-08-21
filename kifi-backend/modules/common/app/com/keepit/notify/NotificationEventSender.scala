package com.keepit.notify

import com.keepit.notify.info._
import com.keepit.notify.model.event.NotificationEvent

import scala.concurrent.{ExecutionContext, Future}

trait NotificationEventSender {

  implicit val executionContext: ExecutionContext

  def send[N <: NotificationEvent](wrappedEvent: ExistingDbView[N]): Unit = {
    val event = wrappedEvent.result
    val infoFut = process(event.kind.info(Set(event)), Some(wrappedEvent))
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
        else processRequest(request, view)
      }
    }
  }

  def processRequest[M <: HasId[M], R](request: DbViewRequest[M, R], view: DbView): Future[DbView] = {
    getRequest(request).map(result => view.add(request.key, request.id, result))
  }

  def getRequest[M <: HasId[M], R](request: DbViewRequest[M, R]): Future[R]

}
