package com.keepit.shoebox

import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.net.HttpClient
import com.keepit.common.db.Id
import com.keepit.model._
import scala.concurrent.Future
import com.keepit.controllers.shoebox._
import com.keepit.controllers.shoebox.ShoeboxController
import com.keepit.serializer.NormalizedURISerializer
import play.api.libs.json.{JsArray, JsValue, Json}
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.libs.json.JsNumber

trait ShoeboxServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SHOEBOX

  def getUser(id: Id[User]): Future[User]
  def getNormalizedURI(id: Long) : Future[NormalizedURI]
  def getNormalizedURIs(ids: Seq[Long]): Future[Seq[NormalizedURI]]
}
class ShoeboxServiceClientImpl(override val host: String, override val port: Int, override val httpClient: HttpClient)
    extends ShoeboxServiceClient {
  def getUser(id: Id[User]): Future[User] = {
    //call(routes.ShoeboxController.getUser(id)).map(r => UserSerializer.userSerializer.reads(r.json))
    ???
  }

  def getNormalizedURI(id: Long) : Future[NormalizedURI] = {
    call(routes.ShoeboxController.getNormalizedURI(id)).map(r => NormalizedURISerializer.normalizedURISerializer.reads(r.json).get)
  }

  def getNormalizedURIs(ids: Seq[Long]): Future[Seq[NormalizedURI]] = {
    val idJarray = JsArray(ids.map(JsNumber(_)))
    call(routes.ShoeboxController.getNormalizedURIs, idJarray).map { r =>
      r.json.as[JsArray].value.map(js => NormalizedURISerializer.normalizedURISerializer.reads(js).get)
    }
  }
}

