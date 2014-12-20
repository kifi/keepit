package com.keepit.curator

import com.keepit.model.{ UriRecommendationFeedback, NormalizedURI, UriRecommendationScores, User }

import scala.concurrent.Future
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.service.ServiceType
import com.google.inject.util.Providers
import com.keepit.common.actor.FakeScheduler
import com.keepit.common.db.Id
import com.keepit.curator.model.{ RecommendationSubSource, LibraryRecoSelectionParams, LibraryRecoInfo, RecoInfo, RecommendationSource }
import collection.mutable.ListBuffer

class FakeCuratorServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier) extends CuratorServiceClient {
  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE, Providers.of(airbrakeNotifier), new FakeScheduler(), () => {})
  protected def httpClient: com.keepit.common.net.HttpClient = ???

  def adHocRecos(userId: Id[User], n: Int, scoreCoefficientsUpdate: UriRecommendationScores): Future[Seq[RecoInfo]] = Future.successful(Seq.empty)

  var fakeTopRecos: Map[Id[User], Seq[RecoInfo]] = Map.empty

  def topRecos(userId: Id[User], source: RecommendationSource, subSource: RecommendationSubSource, more: Boolean, recencyWeight: Float): Future[Seq[RecoInfo]] =
    Future.successful(fakeTopRecos.getOrElse(userId, Seq[RecoInfo]()))

  def topPublicRecos(userId: Option[Id[User]]): Future[Seq[RecoInfo]] = Future.successful(Seq.empty)

  def generalRecos(): Future[Seq[RecoInfo]] = Future.successful(Seq.empty)

  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback): Future[Boolean] = {
    updatedUriRecommendationFeedback.append((userId, uriId, feedback))
    Future.successful(true)
  }

  def triggerEmailToUser(code: String, userId: Id[User]): Future[String] = Future.successful("done")

  def refreshUserRecos(userId: Id[User]): Future[Unit] = { Future.successful() }

  def topLibraryRecos(userId: Id[User], limit: Option[Int] = None): Future[Seq[LibraryRecoInfo]] =
    Future.successful(topLibraryRecosExpectations(userId))

  def refreshLibraryRecos(userId: Id[User], await: Boolean = false, selectionParams: Option[LibraryRecoSelectionParams] = None): Future[Unit] = {
    Future.successful()
  }

  // test helpers
  val updatedUriRecommendationFeedback = ListBuffer[(Id[User], Id[NormalizedURI], UriRecommendationFeedback)]()
  val topLibraryRecosExpectations = collection.mutable.Map[Id[User], Seq[LibraryRecoInfo]]().withDefaultValue(Seq.empty)

}
