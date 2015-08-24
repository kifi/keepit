package com.keepit.notify.info

import com.keepit.common.db.Id

import scala.concurrent.Future

/**
 * Responds to db view requests.
 */
trait DbViewRequestHandler {

  val handlers: DbViewRequestHandlers

  def apply[M <: HasId[M], R](key: DbViewKey[M, R], requests: Seq[DbViewRequest[M, R]]): Future[Map[Id[M], R]] =
    handlers.apply(key, requests)

}

class DbViewRequestHandlers(private val handlers: Map[ExDbViewKey, Seq[ExDbViewRequest] => Future[Map[Id[Any], Any]]]) {

  def apply[M <: HasId[M], R](key: DbViewKey[M, R], requests: Seq[DbViewRequest[M, R]]): Future[Map[Id[M], R]] = {
    handlers.get(key).fold(Future.failed[Map[Id[M], R]](new IllegalArgumentException(s"Key $key has no registered handler"))) { handler =>
      handler(requests).asInstanceOf[Future[Map[Id[M], R]]]
    }
  }

  def add[M <: HasId[M], R](key: DbViewKey[M, R])(handler: Seq[DbViewRequest[M, R]] => Future[Map[Id[M], R]]) = {
    new DbViewRequestHandlers(handlers + (key -> handler.asInstanceOf[Seq[ExDbViewRequest] => Future[Map[Id[Any], Any]]]))
  }

}

object DbViewRequestHandlers {

  def apply() = new DbViewRequestHandlers(Map())

}
