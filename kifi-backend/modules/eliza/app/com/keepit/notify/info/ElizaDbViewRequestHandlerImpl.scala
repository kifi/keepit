package com.keepit.notify.info

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.shoebox.ShoeboxServiceClient

import scala.concurrent.Future

@Singleton
class ElizaDbViewRequestHandlerImpl @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient) extends DbViewRequestHandler {

  val handlers = DbViewRequestHandlers()

}
