package com.keepit.graph

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.service.{ RequestConsolidator, ServiceClient, ServiceType }
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.net.{ CallTimeouts, ClientResponse, HttpClient }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.graph.model._
import com.keepit.model.{ SocialUserInfo, NormalizedURI, User }
import scala.concurrent.{ Promise, Future }
import com.keepit.common.routes.{ ServiceRoute, Graph }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.amazon.AmazonInstanceId
import com.keepit.graph.manager.{ PrettyGraphState, PrettyGraphStatistics }
import play.api.Mode
import play.api.Mode.Mode
import com.keepit.graph.wander.{ Wanderlust, Collisions }
import play.api.libs.json.{ Json }
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
  def getConnectedUriScores(userId: Id[User], avoidFirstDegreeConnections: Boolean): Future[Seq[ConnectedUriScore]]
  def getConnectedUserScores(userId: Id[User], avoidFirstDegreeConnections: Boolean): Future[Seq[ConnectedUserScore]]
  def refreshSociallyRelatedEntities(userId: Id[User]): Future[Unit]
  def getUserFriendships(userId: Id[User], bePatient: Boolean): Future[Seq[(Id[User], Double)]]
  def getSociallyRelatedUsers(userId: Id[User], bePatient: Boolean): Future[Option[RelatedEntities[User, User]]]
  def getSociallyRelatedFacebookAccounts(userId: Id[User], bePatient: Boolean): Future[Option[RelatedEntities[User, SocialUserInfo]]]
  def getSociallyRelatedLinkedInAccounts(userId: Id[User], bePatient: Boolean): Future[Option[RelatedEntities[User, SocialUserInfo]]]
  def getSociallyRelatedEmailAccounts(userId: Id[User], bePatient: Boolean): Future[Option[RelatedEntities[User, EmailAccountInfo]]]
  def explainFeed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[GraphFeedExplanation]]
}

case class GraphCacheProvider @Inject() (
  userScoreCache: ConnectedUserScoreCache,
  uriScoreCache: ConnectedUriScoreCache,
  relatedUsersCache: SociallyRelatedUsersCache,
  relatedFacebookAccountsCache: SociallyRelatedFacebookAccountsCache,
  relatedLinkedInAccountsCache: SociallyRelatedLinkedInAccountsCache,
  relatedEmailAccountsCache: SociallyRelatedEmailAccountsCache)

class GraphServiceClientImpl @Inject() (
    override val serviceCluster: ServiceCluster,
    override val httpClient: HttpClient,
    val airbrakeNotifier: AirbrakeNotifier,
    cacheProvider: GraphCacheProvider,
    mode: Mode) extends GraphServiceClient {

  private val longTimeout = CallTimeouts(responseTimeout = Some(300000), maxWaitTime = Some(3000), maxJsonParseTime = Some(10000))

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

  private val consolidateSociallyRelatedEntities = new RequestConsolidator[Id[User], Unit](10 seconds)
  def refreshSociallyRelatedEntities(userId: Id[User]): Future[Unit] = consolidateSociallyRelatedEntities(userId) { id =>
    call(Graph.internal.refreshSociallyRelatedEntities(id), callTimeouts = longTimeout).map(_ => ())
  }

  def getSociallyRelatedUsers(userId: Id[User], bePatient: Boolean): Future[Option[RelatedEntities[User, User]]] = {
    def cached(id: Id[User]) = cacheProvider.relatedUsersCache.get(SociallyRelatedUsersCacheKey(id))
    getOrElseRefreshRelatedEntities(userId, bePatient, cached, Graph.internal.getSociallyRelatedUsers, refreshSociallyRelatedEntities)
  }

  def getSociallyRelatedFacebookAccounts(userId: Id[User], bePatient: Boolean): Future[Option[RelatedEntities[User, SocialUserInfo]]] = {
    def cached(id: Id[User]) = cacheProvider.relatedFacebookAccountsCache.get(SociallyRelatedFacebookAccountsCacheKey(id))
    getOrElseRefreshRelatedEntities(userId, bePatient, cached, Graph.internal.getSociallyRelatedFacebookAccounts, refreshSociallyRelatedEntities)
  }

  def getSociallyRelatedLinkedInAccounts(userId: Id[User], bePatient: Boolean): Future[Option[RelatedEntities[User, SocialUserInfo]]] = {
    def cached(id: Id[User]) = cacheProvider.relatedLinkedInAccountsCache.get(SociallyRelatedLinkedInAccountsCacheKey(id))
    getOrElseRefreshRelatedEntities(userId, bePatient, cached, Graph.internal.getSociallyRelatedLinkedInAccounts, refreshSociallyRelatedEntities)
  }

  def getSociallyRelatedEmailAccounts(userId: Id[User], bePatient: Boolean): Future[Option[RelatedEntities[User, EmailAccountInfo]]] = {
    def cached(id: Id[User]) = cacheProvider.relatedEmailAccountsCache.get(SociallyRelatedEmailAccountsCacheKey(id))
    getOrElseRefreshRelatedEntities(userId, bePatient, cached, Graph.internal.getSociallyRelatedEmailAccounts, refreshSociallyRelatedEntities)
  }

  private def getOrElseRefreshRelatedEntities[E, R](id: Id[E], bePatient: Boolean, get: Id[E] => Option[RelatedEntities[E, R]], orElseCall: Id[E] => ServiceRoute, refresh: Id[E] => Future[Unit]) = {
    get(id) match {
      case Some(relatedEntities) => Future.successful(Some(relatedEntities))
      case None => {
        if (bePatient) call(orElseCall(id), callTimeouts = longTimeout).map { r => Some(r.json.as[RelatedEntities[E, R]]) }
        else {
          refresh(id)
          Future.successful(None)
        }
      }
    }
  }

  def explainFeed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[GraphFeedExplanation]] = {
    val payload = Json.obj("user" -> userId, "uris" -> uriIds)
    call(Graph.internal.explainFeed(), payload, callTimeouts = longTimeout).map { r => (r.json).as[Seq[GraphFeedExplanation]] }
  }
}
