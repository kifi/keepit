package com.keepit.curator

import com.keepit.model._

import scala.concurrent.Future
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.service.ServiceType
import com.google.inject.util.Providers
import com.keepit.common.actor.FakeScheduler
import com.keepit.common.db.Id
import com.keepit.curator.model._
import collection.mutable.ListBuffer

class FakeCuratorServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier) extends CuratorServiceClient {
  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE, Providers.of(airbrakeNotifier), new FakeScheduler(), () => {})
  protected def httpClient: com.keepit.common.net.HttpClient = ???

  def adHocRecos(userId: Id[User], n: Int, scoreCoefficientsUpdate: UriRecommendationScores): Future[Seq[RecoInfo]] = Future.successful(Seq.empty)

  var fakeTopRecos: Map[Id[User], Seq[RecoInfo]] = Map.empty

  def topRecos(userId: Id[User], source: RecommendationSource, subSource: RecommendationSubSource, more: Boolean, recencyWeight: Float, context: Option[String]): Future[URIRecoResults] =
    Future.successful {
      val recos = fakeTopRecos.getOrElse(userId, Seq[RecoInfo]())
      URIRecoResults(recos, "")
    }

  def topPublicRecos(userId: Option[Id[User]]): Future[Seq[RecoInfo]] = Future.successful(Seq.empty)

  def generalRecos(): Future[Seq[RecoInfo]] = Future.successful(Seq.empty)

  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback): Future[Boolean] = {
    updatedUriRecommendationFeedback.append((userId, uriId, feedback))
    Future.successful(true)
  }

  def updateLibraryRecommendationFeedback(userId: Id[User], libraryId: Id[Library], feedback: LibraryRecommendationFeedback): Future[Boolean] = {
    updatedLibraryRecommendationFeedback.append((userId, libraryId, feedback))
    Future.successful(true)
  }

  def triggerEmailToUser(code: String, userId: Id[User]): Future[String] = Future.successful("done")

  def refreshUserRecos(userId: Id[User]): Future[Unit] = { Future.successful(()) }

  def topLibraryRecos(userId: Id[User], limit: Option[Int] = None, context: Option[String]): Future[LibraryRecoResults] =
    Future.successful {
      val recos = topLibraryRecosExpectations(userId)
      LibraryRecoResults(recos, "")
    }

  def refreshLibraryRecos(userId: Id[User], await: Boolean = false, selectionParams: Option[LibraryRecoSelectionParams] = None): Future[Unit] = {
    Future.successful(())
  }

  def notifyLibraryRecosDelivered(userId: Id[User], libraryIds: Set[Id[Library]], source: RecommendationSource, subSource: RecommendationSubSource): Future[Unit] = {
    Future.successful(())
  }

  def ingestPersonaRecos(userId: Id[User], personaIds: Seq[Id[Persona]], reverseIngestion: Boolean = false): Future[Unit] = Future.successful(())

  // test helpers
  val updatedUriRecommendationFeedback = ListBuffer[(Id[User], Id[NormalizedURI], UriRecommendationFeedback)]()
  val updatedLibraryRecommendationFeedback = ListBuffer[(Id[User], Id[Library], LibraryRecommendationFeedback)]()
  val topLibraryRecosExpectations = collection.mutable.Map[Id[User], Seq[LibraryRecoInfo]]().withDefaultValue(Seq.empty)

}
