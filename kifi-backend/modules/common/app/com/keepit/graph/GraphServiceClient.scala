package com.keepit.graph

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.service.{ RequestConsolidator, ServiceClient, ServiceType }
import com.keepit.common.zookeeper.{ ServiceCluster }
import com.keepit.common.net.{ CallTimeouts, HttpClient }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.graph.model._
import com.keepit.model.{ Organization, NormalizedURI, User }
import scala.concurrent.Future
import com.keepit.common.time._
import com.keepit.common.routes.Graph
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.amazon.{ AmazonInstanceInfo }
import com.keepit.graph.manager.{ PrettyGraphState, PrettyGraphStatistics }
import play.api.Mode
import play.api.Mode.Mode
import com.keepit.graph.wander.{ Wanderlust, Collisions }
import play.api.libs.json.Json
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.graph.model.GraphKinds
import scala.concurrent.duration._
import scala.util.Success
import com.keepit.common.core._

trait GraphServiceClient extends ServiceClient {
  final val serviceType = ServiceType.GRAPH

  def getGraphStatistics(): Future[Map[AmazonInstanceInfo, PrettyGraphStatistics]]
  def getGraphUpdaterStates(): Future[Map[AmazonInstanceInfo, PrettyGraphState]]
  def getGraphKinds(): Future[GraphKinds]
  def wander(wanderlust: Wanderlust): Future[Collisions]
  def getConnectedUriScores(userId: Id[User], avoidFirstDegreeConnections: Boolean): Future[Seq[ConnectedUriScore]]
  def getConnectedUserScores(userId: Id[User], avoidFirstDegreeConnections: Boolean): Future[Seq[ConnectedUserScore]]
  def getSociallyRelatedEntitiesForUser(userId: Id[User]): Future[Option[SociallyRelatedEntitiesForUser]]
  def getSociallyRelatedEntitiesForOrg(orgId: Id[Organization]): Future[Option[SociallyRelatedEntitiesForOrg]]
  def refreshSociallyRelatedEntitiesForUser(userId: Id[User]): Future[Unit]
  def refreshSociallyRelatedEntitiesForOrg(orgId: Id[Organization]): Future[Unit]
  def explainFeed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[GraphFeedExplanation]]
}

case class GraphCacheProvider @Inject() (
  userScoreCache: ConnectedUserScoreCache,
  uriScoreCache: ConnectedUriScoreCache,
  relatedEntitiesForUserCache: SociallyRelatedEntitiesForUserCache,
  relatedEntitiesForOrgCache: SociallyRelatedEntitiesForOrgCache)

class GraphServiceClientImpl(
    override val serviceCluster: ServiceCluster,
    override val httpClient: HttpClient,
    val airbrakeNotifier: AirbrakeNotifier,
    cacheProvider: GraphCacheProvider,
    mode: Mode) extends GraphServiceClient with Logging {

  private val longTimeout = CallTimeouts(responseTimeout = Some(300000), maxWaitTime = Some(3000), maxJsonParseTime = Some(10000))

  private[this] val connectedUserScoresReqConsolidator = new RequestConsolidator[(Id[User], Boolean), Seq[ConnectedUserScore]](1 minutes)

  def getGraphStatistics(): Future[Map[AmazonInstanceInfo, PrettyGraphStatistics]] = {
    //    collectResponses(broadcast(Graph.internal.getGraphStatistics(), includeUnavailable = true, includeSelf = (mode == Mode.Dev))).map { responses =>
    //      responses.collect { case (instance, Success(successfulResponse)) => instance -> (successfulResponse.json.as[PrettyGraphStatistics]) }
    //    }
    Future.successful(Map.empty)
  }

  def getGraphUpdaterStates(): Future[Map[AmazonInstanceInfo, PrettyGraphState]] = {
    //    collectResponses(broadcast(Graph.internal.getGraphUpdaterState(), includeUnavailable = true, includeSelf = (mode == Mode.Dev))).map { responses =>
    //      responses.collect { case (instance, Success(successfulResponse)) => instance -> (successfulResponse.json.as[PrettyGraphState]) }
    //    }
    Future.successful(Map.empty)
  }

  def getGraphKinds(): Future[GraphKinds] = {
    //call(Graph.internal.getGraphKinds()).map { response => response.json.as[GraphKinds] }
    Future.successful(GraphKinds(Set.empty, Set.empty))
  }

  def wander(wanderlust: Wanderlust): Future[Collisions] = {
    val payload = Json.toJson(wanderlust)
    //call(Graph.internal.wander(), payload, callTimeouts = longTimeout).map { response => response.json.as[Collisions] }
    Future.successful(Collisions(Map.empty, Map.empty, Map.empty, Map.empty, Map.empty))
  }

  def getConnectedUriScores(userId: Id[User], avoidFirstDegreeConnections: Boolean): Future[Seq[ConnectedUriScore]] = {
    Future.successful(cacheProvider.uriScoreCache.get(ConnectedUriScoreCacheKey(userId, avoidFirstDegreeConnections)).getOrElse(Seq.empty))
  }

  def getConnectedUserScores(userId: Id[User], avoidFirstDegreeConnections: Boolean): Future[Seq[ConnectedUserScore]] = {
    connectedUserScoresReqConsolidator((userId, avoidFirstDegreeConnections)) { _ =>
      Future.successful(cacheProvider.userScoreCache.get(ConnectedUserScoreCacheKey(userId, avoidFirstDegreeConnections)).getOrElse(Seq.empty))
    }
  }

  def getSociallyRelatedEntitiesForUser(userId: Id[User]): Future[Option[SociallyRelatedEntitiesForUser]] = {

    Future.successful(cacheProvider.relatedEntitiesForUserCache.get(SociallyRelatedEntitiesForUserCacheKey(userId)))
  }

  def getSociallyRelatedEntitiesForOrg(orgId: Id[Organization]): Future[Option[SociallyRelatedEntitiesForOrg]] = {
    Future.successful(cacheProvider.relatedEntitiesForOrgCache.get(SociallyRelatedEntitiesForOrgCacheKey(orgId)))
  }

  def refreshSociallyRelatedEntitiesForUser(userId: Id[User]): Future[Unit] = {
    cacheProvider.relatedEntitiesForUserCache.remove(SociallyRelatedEntitiesForUserCacheKey(userId))
    //getSociallyRelatedEntitiesForUser(userId).imap(_ => ())
    Future.successful(())
  }

  def refreshSociallyRelatedEntitiesForOrg(orgId: Id[Organization]): Future[Unit] = {
    cacheProvider.relatedEntitiesForOrgCache.remove(SociallyRelatedEntitiesForOrgCacheKey(orgId))
    //getSociallyRelatedEntitiesForOrg(orgId).imap(_ => ())
    Future.successful(())
  }

  def explainFeed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[GraphFeedExplanation]] = {
    val payload = Json.obj("user" -> userId, "uris" -> uriIds)
    //call(Graph.internal.explainFeed(), payload, callTimeouts = longTimeout).map { r => (r.json).as[Seq[GraphFeedExplanation]] }
    Future.successful(Seq.empty)
  }
}
