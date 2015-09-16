package com.keepit.curator

import com.keepit.common.service.{ ServiceClient, ServiceType }
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.{ HttpClient, CallTimeouts }
import com.keepit.common.routes.Curator
import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.curator.model._

import scala.concurrent.{ ExecutionContext, Future }
import play.api.libs.json._

trait CuratorServiceClient extends ServiceClient {
  final val serviceType = ServiceType.CURATOR

  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback): Future[Boolean]
  def updateLibraryRecommendationFeedback(userId: Id[User], libraryId: Id[Library], feedback: LibraryRecommendationFeedback): Future[Boolean]
  def triggerEmailToUser(code: String, userId: Id[User]): Future[String]
  def topLibraryRecos(userId: Id[User], limit: Option[Int] = None, context: Option[String]): Future[LibraryRecoResults]
}

class CuratorServiceClientImpl(
    override val serviceCluster: ServiceCluster,
    override val httpClient: HttpClient,
    implicit val defaultContext: ExecutionContext,
    val airbrakeNotifier: AirbrakeNotifier) extends CuratorServiceClient {

  val longTimeout = CallTimeouts(responseTimeout = Some(30000), maxWaitTime = Some(10000), maxJsonParseTime = Some(10000))

  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback): Future[Boolean] = {
    call(Curator.internal.updateUriRecommendationFeedback(userId, uriId), body = Json.toJson(feedback), callTimeouts = longTimeout).map(response =>
      response.json.as[Boolean]
    )
  }

  def updateLibraryRecommendationFeedback(userId: Id[User], libraryId: Id[Library], feedback: LibraryRecommendationFeedback): Future[Boolean] = {
    call(Curator.internal.updateLibraryRecommendationFeedback(userId, libraryId), body = Json.toJson(feedback), callTimeouts = longTimeout).map(response =>
      response.json.as[Boolean]
    )
  }

  def triggerEmailToUser(code: String, userId: Id[User]) = {
    call(Curator.internal.triggerEmailToUser(code, userId), callTimeouts = longTimeout).map { response =>
      response.json.as[String]
    }
  }

  def topLibraryRecos(userId: Id[User], limit: Option[Int] = None, context: Option[String]): Future[LibraryRecoResults] = {
    val payload = Json.obj("context" -> JsString(context.getOrElse("")))
    call(Curator.internal.topLibraryRecos(userId, limit), payload).map { response =>
      val js = response.json
      js.validate[LibraryRecoResults] match {
        case res: JsSuccess[LibraryRecoResults] => res.get
        case err: JsError =>
          val recos = js.as[Seq[LibraryRecoInfo]]
          LibraryRecoResults(recos, "")
      }
    }
  }
}
