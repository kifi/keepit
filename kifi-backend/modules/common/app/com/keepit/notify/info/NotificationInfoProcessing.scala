package com.keepit.notify.info

import com.google.inject.Inject
import com.keepit.notify.model.event.NotificationEvent

import scala.concurrent.{ExecutionContext, Future}

class NotificationInfoProcessing @Inject() (
  requestHandler: DbViewRequestHandler,
  implicit val ec: ExecutionContext
) {

  def process[N <: NotificationEvent](usingView: UsingDbView[NotificationInfo]): Future[NotificationInfo] = {
    val info = getView(usingView.requests)
    info.map { view =>
      usingView.fn(view)
    }
  }

  def getView(requests: Seq[ExDbViewRequest]): Future[DbView] = {
    val grouped = requests.groupBy(_.key)
    // We know that after the groupBy, the type of requests is the same because they have the same key.
    // This cast also makes the compiler know
    grouped.toList.asInstanceOf[List[(DbViewKey[M, R], Seq[DbViewRequest[M, R]]) forSome { type M <: HasId[M]; type R}]]
    grouped.foldLeft(Future.successful(DbView()))(genericFoldGrouped)
  }

  // Turns the type-parameterized fold into an existential fold. We know the relationship between the key and requests in
  // the foldGrouped, but the compiler isn't very helpful with polymorphic function values in the fold.
  val genericFoldGrouped = (foldGrouped _).asInstanceOf[(Future[DbView], (ExDbViewKey, Seq[ExDbViewRequest])) => Future[DbView]]

  def foldGrouped(viewFut: Future[DbView], keyAndReqs: (DbViewKey[M, R], Seq[DbViewRequest[M, R]]) forSome { type M <: HasId[M]; type R}): Future[DbView] =
    keyAndReqs match {
      case (key, reqs) =>  viewFut.flatMap { view =>
        val toRequest = reqs.filter(req => !view.contains(key, req.id))
        requestHandler(key, toRequest).map { keyMap =>
          keyMap.toList.foldLeft(view) {
            case (viewAcc, (id, result)) => viewAcc.update(key, id, result)
          }
        }
      }
    }

}
