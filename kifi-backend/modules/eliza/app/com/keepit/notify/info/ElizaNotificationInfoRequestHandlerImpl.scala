package com.keepit.notify.info

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.shoebox.ShoeboxServiceClient

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class ElizaNotificationInfoRequestHandlerImpl @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient,
    implicit val ec: ExecutionContext) extends NotificationInfoRequestHandler {

  override def apply[M <: HasId[M], R](key: NotificationInfoRequest[M, R], requests: Seq[NotificationInfoRequest[M, R]]): Future[Map[Id[M], R]] = ???

}
