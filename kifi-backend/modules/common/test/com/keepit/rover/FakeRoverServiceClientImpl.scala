package com.keepit.rover

import com.keepit.common.db.SequenceNumber
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.net.HttpClient
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.rover.model.{ ShoeboxArticleUpdates, ArticleInfo }

import scala.concurrent.Future

class FakeRoverServiceClientImpl(
    override val serviceCluster: ServiceCluster,
    override val httpClient: HttpClient,
    val airbrakeNotifier: AirbrakeNotifier) extends RoverServiceClient {

  def getShoeboxUpdates(seq: SequenceNumber[ArticleInfo], limit: Int): Future[Option[ShoeboxArticleUpdates]] = Future.successful(None)
}
