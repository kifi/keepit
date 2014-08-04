package com.keepit.curator

import com.keepit.common.service.{ ServiceClient, ServiceType }
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.{ HttpClient, CallTimeouts }
import com.keepit.common.routes.Curator
import com.keepit.common.db.Id
import com.keepit.model.ScoreType._
import com.keepit.model.{ ScoreType, User }
import com.keepit.curator.model.Recommendation
import com.keepit.common.util.MapFormatUtil.scoreTypeMapFormat

import scala.concurrent.Future
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

trait CuratorServiceClient extends ServiceClient {
  final val serviceType = ServiceType.CURATOR

  def adHocRecos(userId: Id[User], n: Int, scoreCoefficientsUpdate: Map[ScoreType.Value, Float]): Future[Seq[Recommendation]]
}

class CuratorServiceClientImpl(
    override val serviceCluster: ServiceCluster,
    override val httpClient: HttpClient,
    val airbrakeNotifier: AirbrakeNotifier) extends CuratorServiceClient {

  val longTimeout = CallTimeouts(responseTimeout = Some(30000), maxWaitTime = Some(3000), maxJsonParseTime = Some(10000))

  def adHocRecos(userId: Id[User], n: Int, scoreCoefficientsUpdate: Map[ScoreType.Value, Float]): Future[Seq[Recommendation]] = {
    call(Curator.internal.adHocRecos(userId, n), body = Json.toJson(scoreCoefficientsUpdate), callTimeouts = longTimeout).map { response =>
      response.json.as[Seq[Recommendation]]
    }
  }

}
