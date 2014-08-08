package com.keepit.curator

import com.keepit.model.{ UriRecommendationUserInteraction, UriRecommendationFeedback, NormalizedURI, UriRecommendationScores, User }

import scala.concurrent.Future
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.service.ServiceType
import com.google.inject.util.Providers
import com.keepit.common.actor.FakeScheduler
import com.keepit.common.db.Id
import com.keepit.curator.model.RecommendationInfo

class FakeCuratorServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier) extends CuratorServiceClient {
  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE, Providers.of(airbrakeNotifier), new FakeScheduler(), () => {})
  protected def httpClient: com.keepit.common.net.HttpClient = ???

  def adHocRecos(userId: Id[User], n: Int, scoreCoefficientsUpdate: UriRecommendationScores): Future[Seq[RecommendationInfo]] = Future.successful(Seq.empty)
  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback): Future[Boolean] = Future.successful(true)
  def triggerEmail(code: String): Future[String] = Future.successful("done")
  def triggerEmailToUser(code: String, userId: Id[User]): Future[String] = Future.successful("done")
  def updateUriRecommendationUserInteraction(userId: Id[User], uriId: Id[NormalizedURI], interaction: UriRecommendationUserInteraction): Future[Boolean] = Future.successful(true)
}
