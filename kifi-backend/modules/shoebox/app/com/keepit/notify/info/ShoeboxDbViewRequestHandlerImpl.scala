package com.keepit.notify.info

import com.google.inject.{Inject, Singleton}

import scala.concurrent.Future

@Singleton
class ShoeboxDbViewRequestHandlerImpl @Inject() (

) extends DbViewRequestHandler {

  override def apply[M <: HasId[M], R](request: Seq[DbViewRequest[M, R]]): Future[Seq[R]] = ???

}
