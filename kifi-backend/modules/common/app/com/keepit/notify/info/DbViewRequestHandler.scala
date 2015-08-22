package com.keepit.notify.info

import scala.concurrent.Future

/**
 * Responds to db view requests.
 */
trait DbViewRequestHandler {

  def apply[M <: HasId[M], R](request: Seq[DbViewRequest[M, R]]): Future[Seq[R]]

}
