package com.keepit.notify.info

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.shoebox.ShoeboxServiceClient

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class ElizaDbViewRequestHandlerImpl @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient,
    implicit val ec: ExecutionContext) extends DbViewRequestHandler {

  override def apply[M <: HasId[M], R](key: DbViewKey[M, R], requests: Seq[DbViewRequest[M, R]]): Future[Map[Id[M], R]] = ???
  
}
