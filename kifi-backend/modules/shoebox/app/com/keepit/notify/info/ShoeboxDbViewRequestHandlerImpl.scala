package com.keepit.notify.info

import com.google.inject.{Inject, Singleton}
import com.keepit.common.db.Id
import com.keepit.model.UserRepo

import scala.concurrent.Future

/**
 * A DB view request handler on the shoebox side. This should almost never be used, perhaps only a when
 * retrieving information as a notification event is being created.
 */
@Singleton
class ShoeboxDbViewRequestHandlerImpl @Inject() (
  userRepo: UserRepo
) extends DbViewRequestHandler {

  override def apply[M <: HasId[M], R](key: DbViewKey[M, R], requests: Seq[DbViewRequest[M, R]]): Future[Map[Id[M], R]] = ???

}
