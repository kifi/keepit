package com.keepit.notify.info

import com.keepit.common.db.Id

import scala.concurrent.Future

/**
 * Responds to db view requests.
 */
trait DbViewRequestHandler {

  def apply[M <: HasId[M], R](key: DbViewKey[M, R], requests: Seq[DbViewRequest[M, R]]): Future[Map[Id[M], R]]

}
