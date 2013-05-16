package com.keepit.shoebox

import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.net.HttpClient
import com.keepit.common.db.Id
import com.keepit.model._
import scala.concurrent.Future
//import com.keepit.controllers.shoebox._ // needs to be created

trait ShoeboxServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SHOEBOX
  
  def getUser(id: Id[User]): Future[User]
}
class ShoeboxServiceClientImpl(override val host: String, override val port: Int, override val httpClient: HttpClient)
    extends ShoeboxServiceClient {
  def getUser(id: Id[User]): Future[User] = {
    //call(routes.ShoeboxController.getUser(id)).map(r => UserSerializer.userSerializer.reads(r.json))
    ???
  }
}

