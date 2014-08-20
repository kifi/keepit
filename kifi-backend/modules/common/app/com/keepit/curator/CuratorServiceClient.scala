package com.keepit.curator

import com.keepit.common.service.{ ServiceClient, ServiceType }
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.{ HttpClient, CallTimeouts }
import com.keepit.common.routes.Curator
import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.curator.model.RecommendationInfo

import scala.concurrent.Future
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

trait CuratorServiceClient extends ServiceClient {
  final val serviceType = ServiceType.CURATOR

  def adHocRecos(userId: Id[User], n: Int, scoreCoefficientsUpdate: UriRecommendationScores): Future[Seq[RecommendationInfo]]
  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback): Future[Boolean]
  def triggerEmail(code: String): Future[String]
  def triggerEmailToUser(code: String, userId: Id[User]): Future[String]
  def updateUriRecommendationUserInteraction(userId: Id[User], uriId: Id[NormalizedURI], interaction: UriRecommendationUserInteraction): Future[Boolean]
  def resetUserRecomGenState(userId: Id[User]): Future[Unit]
}

class CuratorServiceClientImpl(
    override val serviceCluster: ServiceCluster,
    override val httpClient: HttpClient,
    val airbrakeNotifier: AirbrakeNotifier) extends CuratorServiceClient {

  val longTimeout = CallTimeouts(responseTimeout = Some(30000), maxWaitTime = Some(3000), maxJsonParseTime = Some(10000))

  def adHocRecos(userId: Id[User], n: Int, scoreCoefficientsUpdate: UriRecommendationScores): Future[Seq[RecommendationInfo]] = {
    call(Curator.internal.adHocRecos(userId, n), body = Json.toJson(scoreCoefficientsUpdate), callTimeouts = longTimeout).map { response =>
      response.json.as[Seq[RecommendationInfo]]
    }
  }

  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback): Future[Boolean] = {
    call(Curator.internal.updateUriRecommendationFeedback(userId, uriId), body = Json.toJson(feedback), callTimeouts = longTimeout).map(response =>
      response.json.as[Boolean]
    )
  }

  def triggerEmail(code: String) = {
    call(Curator.internal.triggerEmail(code), callTimeouts = longTimeout).map { response =>
      response.json.as[String]
    }
  }

  def triggerEmailToUser(code: String, userId: Id[User]) = {
    call(Curator.internal.triggerEmailToUser(code, userId), callTimeouts = longTimeout).map { response =>
      response.json.as[String]
    }
  }

  def updateUriRecommendationUserInteraction(userId: Id[User], uriId: Id[NormalizedURI], interaction: UriRecommendationUserInteraction): Future[Boolean] = {
    call(Curator.internal.updateUriRecommendationUserInteraction(userId, uriId), body = Json.toJson(interaction)).map(response =>
      response.json.as[Boolean]
    )
  }

  def resetUserRecomGenState(userId: Id[User]): Future[Unit] = {
    call(Curator.internal.resetUserRecomGenState(userId)).map { x => }
  }
}
