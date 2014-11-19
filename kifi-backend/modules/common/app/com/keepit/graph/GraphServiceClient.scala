package com.keepit.graph

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.service.{ RequestConsolidator, ServiceClient, ServiceType }
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.net.{ CallTimeouts, ClientResponse, HttpClient }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.graph.model._
import com.keepit.model.{ SocialUserInfo, NormalizedURI, User }
import scala.concurrent.Future
import com.keepit.common.time._
import com.keepit.common.routes.Graph
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.amazon.AmazonInstanceId
import com.keepit.graph.manager.{ PrettyGraphState, PrettyGraphStatistics }
import play.api.Mode
import play.api.Mode.Mode
import com.keepit.graph.wander.{ Wanderlust, Collisions }
import play.api.libs.json.Json
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.graph.model.GraphKinds
import com.keepit.abook.model.EmailAccountInfo
import scala.concurrent.duration._

trait GraphServiceClient extends ServiceClient {
  final val serviceType = ServiceType.GRAPH

  def getGraphStatistics(): Future[Map[AmazonInstanceId, PrettyGraphStatistics]]
  def getGraphUpdaterStates(): Future[Map[AmazonInstanceId, PrettyGraphState]]
  def getGraphKinds(): Future[GraphKinds]
  def wander(wanderlust: Wanderlust): Future[Collisions]
  def uriWander(userId: Id[User], steps: Int): Future[Map[Id[NormalizedURI], Int]]
  def getConnectedUriScores(userId: Id[User], avoidFirstDegreeConnections: Boolean): Future[Seq[ConnectedUriScore]]
  def getConnectedUserScores(userId: Id[User], avoidFirstDegreeConnections: Boolean): Future[Seq[ConnectedUserScore]]
  def getUserFriendships(userId: Id[User], bePatient: Boolean): Future[Seq[(Id[User], Double)]]
  def getSociallyRelatedEntities(userId: Id[User], bePatient: Boolean): Future[Option[SociallyRelatedEntities]]
  def getSociallyRelatedUsers(userId: Id[User], bePatient: Boolean): Future[Option[RelatedEntities[User, User]]]
  def getSociallyRelatedFacebookAccounts(userId: Id[User], bePatient: Boolean): Future[Option[RelatedEntities[User, SocialUserInfo]]]
  def getSociallyRelatedLinkedInAccounts(userId: Id[User], bePatient: Boolean): Future[Option[RelatedEntities[User, SocialUserInfo]]]
  def getSociallyRelatedEmailAccounts(userId: Id[User], bePatient: Boolean): Future[Option[RelatedEntities[User, EmailAccountInfo]]]
  def explainFeed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[GraphFeedExplanation]]
}

case class GraphCacheProvider @Inject() (
  userScoreCache: ConnectedUserScoreCache,
  uriScoreCache: ConnectedUriScoreCache,
  relatedEntitiesCache: SociallyRelatedEntitiesCache)

class GraphServiceClientImpl @Inject() (
    override val serviceCluster: ServiceCluster,
    override val httpClient: HttpClient,
    val airbrakeNotifier: AirbrakeNotifier,
    cacheProvider: GraphCacheProvider,
    mode: Mode) extends GraphServiceClient {

  private val longTimeout = CallTimeouts(responseTimeout = Some(300000), maxWaitTime = Some(3000), maxJsonParseTime = Some(10000))
  private val ONE_HOUR_MILLIS = 1000 * 60 * 60

  private def getSuccessfulResponses(calls: Seq[Future[ClientResponse]]): Future[Seq[ClientResponse]] = {
    val safeCalls = calls.map { call =>
      call.map(Some(_)).recover {
        case error: Throwable =>
          log.error("Failed to complete service call:", error)
          None
      }
    }
    Future.sequence(safeCalls).map(_.flatten)
  }

  def getGraphStatistics(): Future[Map[AmazonInstanceId, PrettyGraphStatistics]] = {
    getSuccessfulResponses(broadcast(Graph.internal.getGraphStatistics(), includeUnavailable = true, includeSelf = (mode == Mode.Dev))).map { responses =>
      responses.map { response =>
        response.request.instance.get.instanceInfo.instanceId -> response.json.as[PrettyGraphStatistics]
      }.toMap
    }
  }

  def getGraphUpdaterStates(): Future[Map[AmazonInstanceId, PrettyGraphState]] = {
    getSuccessfulResponses(broadcast(Graph.internal.getGraphUpdaterState(), includeUnavailable = true, includeSelf = (mode == Mode.Dev))).map { responses =>
      responses.map { response =>
        response.request.instance.get.instanceInfo.instanceId -> response.json.as[PrettyGraphState]
      }.toMap
    }
  }

  def getGraphKinds(): Future[GraphKinds] = {
    call(Graph.internal.getGraphKinds()).map { response => response.json.as[GraphKinds] }
  }

  def wander(wanderlust: Wanderlust): Future[Collisions] = {
    val payload = Json.toJson(wanderlust)
    call(Graph.internal.wander(), payload, callTimeouts = longTimeout).map { response => response.json.as[Collisions] }
  }

  def uriWander(userId: Id[User], steps: Int): Future[Map[Id[NormalizedURI], Int]] = {
    call(Graph.internal.uriWandering(userId, steps), callTimeouts = longTimeout).map { r => (r.json).as[Map[Id[NormalizedURI], Int]] }
  }

  def getConnectedUriScores(userId: Id[User], avoidFirstDegreeConnections: Boolean): Future[Seq[ConnectedUriScore]] = {
    cacheProvider.uriScoreCache.getOrElseFuture(ConnectedUriScoreCacheKey(userId, avoidFirstDegreeConnections)) {
      call(Graph.internal.getUriAndScores(userId, avoidFirstDegreeConnections), callTimeouts = longTimeout).map { response =>
        response.json.as[Seq[ConnectedUriScore]]
      }
    }
  }

  def getConnectedUserScores(userId: Id[User], avoidFirstDegreeConnections: Boolean): Future[Seq[ConnectedUserScore]] = {
    cacheProvider.userScoreCache.getOrElseFuture(ConnectedUserScoreCacheKey(userId, avoidFirstDegreeConnections)) {
      call(Graph.internal.getUserAndScores(userId, avoidFirstDegreeConnections), callTimeouts = longTimeout).map { response =>
        response.json.as[Seq[ConnectedUserScore]]
      }
    }
  }

  def getUserFriendships(userId: Id[User], bePatient: Boolean): Future[Seq[(Id[User], Double)]] = {
    val futureUserScores = cacheProvider.userScoreCache.get(ConnectedUserScoreCacheKey(userId, false)) match {
      case Some(userScores) => Future.successful(userScores)
      case None =>
        val futureActualUserScores = call(Graph.internal.getUserAndScores(userId, false), callTimeouts = longTimeout).map { response =>
          response.json.as[Seq[ConnectedUserScore]]
        }
        if (bePatient) futureActualUserScores else Future.successful(Seq.empty)
    }
    futureUserScores.map(userScores => userScores.map { case ConnectedUserScore(friendId, score) => friendId -> score })
  }

  def getSociallyRelatedEntities(userId: Id[User], bePatient: Boolean): Future[Option[SociallyRelatedEntities]] = {

    def dataIsFresh(x: Option[SociallyRelatedEntities]): Boolean = {
      x.isDefined && (x.get.timestamp.plus(ONE_HOUR_MILLIS).getMillis > currentDateTime.getMillis)
    }

    cacheProvider.relatedEntitiesCache.
      getOrElseFutureOpt(SociallyRelatedEntitiesCacheKey(userId), dataIsFresh) {
        call(Graph.internal.refreshSociallyRelatedEntities(userId), callTimeouts = longTimeout).map { r => Some(r.json.as[SociallyRelatedEntities]) }
      }
  }

  def getSociallyRelatedUsers(userId: Id[User], bePatient: Boolean): Future[Option[RelatedEntities[User, User]]] = {
    getSociallyRelatedEntities(userId, bePatient).map(_.map(_.users))
  }

  def getSociallyRelatedFacebookAccounts(userId: Id[User], bePatient: Boolean): Future[Option[RelatedEntities[User, SocialUserInfo]]] = {
    getSociallyRelatedEntities(userId, bePatient).map(_.map(_.facebookAccounts))
  }

  def getSociallyRelatedLinkedInAccounts(userId: Id[User], bePatient: Boolean): Future[Option[RelatedEntities[User, SocialUserInfo]]] = {
    getSociallyRelatedEntities(userId, bePatient).map(_.map(_.linkedInAccounts))
  }

  def getSociallyRelatedEmailAccounts(userId: Id[User], bePatient: Boolean): Future[Option[RelatedEntities[User, EmailAccountInfo]]] = {
    getSociallyRelatedEntities(userId, bePatient).map(_.map(_.emailAccounts))
  }

  def explainFeed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[GraphFeedExplanation]] = {
    val payload = Json.obj("user" -> userId, "uris" -> uriIds)
    call(Graph.internal.explainFeed(), payload, callTimeouts = longTimeout).map { r => (r.json).as[Seq[GraphFeedExplanation]] }
  }
}
