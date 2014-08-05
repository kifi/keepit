package com.keepit.curator

import com.keepit.common.service.{ ServiceClient, ServiceType }
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.{ HttpClient, CallTimeouts }
import com.keepit.common.routes.Curator
import com.keepit.common.db.Id
import com.keepit.model.ScoreType._
import com.keepit.model.{ UriRecommendationFeedback, NormalizedURI, ScoreType, User }
import com.keepit.curator.model.RecommendationInfo
import com.keepit.common.util.MapFormatUtil.scoreTypeMapFormat
import com.keepit.common.util.MapFormatUtil.uriRecommendationFeedbackMapFormat

import scala.concurrent.Future
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

trait CuratorServiceClient extends ServiceClient {
  final val serviceType = ServiceType.CURATOR

  def adHocRecos(userId: Id[User], n: Int, scoreCoefficientsUpdate: Map[ScoreType.Value, Float]): Future[Seq[RecommendationInfo]]
  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI]): Future[Boolean]

}

class CuratorServiceClientImpl(
    override val serviceCluster: ServiceCluster,
    override val httpClient: HttpClient,
    val airbrakeNotifier: AirbrakeNotifier) extends CuratorServiceClient {

  val longTimeout = CallTimeouts(responseTimeout = Some(30000), maxWaitTime = Some(3000), maxJsonParseTime = Some(10000))

  def adHocRecos(userId: Id[User], n: Int, scoreCoefficientsUpdate: Map[ScoreType.Value, Float]): Future[Seq[RecommendationInfo]] = {
    call(Curator.internal.adHocRecos(userId, n), body = Json.toJson(scoreCoefficientsUpdate), callTimeouts = longTimeout).map { response =>
      response.json.as[Seq[RecommendationInfo]]
    }
  }

  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI]): Future[Boolean] = {
    call(Curator.internal.updateUriRecommendationFeedback(userId, uriId), callTimeouts = longTimeout).map(response =>
      response.json.as[Boolean]
    )
  }
}
