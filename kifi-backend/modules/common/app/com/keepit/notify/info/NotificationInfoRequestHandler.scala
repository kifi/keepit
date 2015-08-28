package com.keepit.notify.info

import com.keepit.common.db.Id

import scala.concurrent.Future

/**
 * Responds to notification info requests.
 */
trait NotificationInfoRequestHandler {

  def apply[M <: HasId[M], R](key: NotificationInfoRequest[M, R], requests: Seq[NotificationInfoRequest[M, R]]): Future[Map[Id[M], R]]

}
