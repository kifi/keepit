package com.keepit.graph.controllers.internal

import com.google.inject.Inject
import com.keepit.common.controller.GraphServiceController
import com.keepit.common.logging.Logging
import com.keepit.graph.commanders.GraphCommander
import com.keepit.graph.manager.{ GraphUpdaterState, GraphStatistics, GraphManager }
import play.api.mvc.Action
import play.api.libs.json._
import com.keepit.graph.wander._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.graph.model._
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

import com.keepit.graph.model.{ EdgeKind, VertexKind, GraphKinds }
import com.keepit.graph.model.VertexKind._
import scala.collection.mutable
import com.keepit.common.db.Id
import com.keepit.model.{ Library, NormalizedURI, SocialUserInfo, User }
import com.keepit.common.time._
import com.keepit.graph.utils.GraphPrimitives
import scala.concurrent.Future
import com.keepit.abook.model.EmailAccountInfo

class GraphController @Inject() (
    graphManager: GraphManager,
    wanderingCommander: WanderingCommander,
    socialWanderingCommander: SocialWanderingCommander,
    feedExplanationCommander: FeedExplanationCommander,
    uriWanderingCommander: URIWanderingCommander,
    graphCommander: GraphCommander) extends GraphServiceController with Logging {

  def wander() = Action.async(parse.json) { request =>
    val wanderlust = request.body.as[Wanderlust]
    wanderingCommander.wander(wanderlust).map { journalOpt =>
      val collisions = journalOpt.map { journal => getCollisions(journal, wanderlust.avoidTrivialCollisions, wanderlust.preferredCollisions.map(VertexKind(_))) } getOrElse (Collisions.empty)
      val json = Json.toJson(collisions)
      Ok(json)
    }
  }

  def getGraphStatistics() = Action { request =>
    val statistics = graphManager.statistics
    val json = Json.toJson(GraphStatistics.prettify(statistics))
    Ok(json)
  }

  def getGraphUpdaterState() = Action { request =>
    val state = graphManager.state
    val json = Json.toJson(GraphUpdaterState.prettify(state))
    Ok(json)
  }

  def getGraphKinds() = Action { request =>
    val graphKinds = GraphKinds(VertexKind.all.map(_.code), EdgeKind.all.map(_.code))
    val json = Json.toJson(graphKinds)
    Ok(json)
  }

  def getListOfUriAndScorePairs(userId: Id[User], avoidFirstDegreeConnections: Boolean) = Action.async { request =>
    graphCommander.getConnectedUriScores(userId, avoidFirstDegreeConnections).map { uriScores =>
      val json = Json.toJson(uriScores)
      Ok(json)
    }
  }

  def getListOfUserAndScorePairs(userId: Id[User], avoidFirstDegreeConnections: Boolean) = Action.async { request =>
    graphCommander.getConnectedUserScores(userId, avoidFirstDegreeConnections).map { userScores =>
      val json = Json.toJson(userScores)
      Ok(json)
    }
  }

  def refreshSociallyRelatedEntities(userId: Id[User]) = Action.async { request =>
    socialWanderingCommander.refresh(userId).map(_ => Ok).recover {
      case VertexNotFoundException(vertexId) if vertexId == VertexId(userId) => Accepted("This user has not been ingested yet.")
    }
  }

  def getSociallyRelatedEntities(userId: Id[User]) = Action.async { request =>
    socialWanderingCommander.refresh(userId).map { relatedPeople => Ok(Json.toJson(relatedPeople)) }
  }

  // todo(Léo): Remove this code once CollisionCommander is operational
  private def getCollisions(journal: TeleportationJournal, avoidTrivialCollisions: Boolean, collisionFilter: Set[VertexType]): Collisions = {
    val forbiddenCollisions = getForbiddenCollisions(journal.getStartingVertex(), avoidTrivialCollisions)

    val users = mutable.Map[Id[User], Int]()
    val socialUsers = mutable.Map[Id[SocialUserInfo], Int]()
    val uris = mutable.Map[Id[NormalizedURI], Int]()
    val libraries = mutable.Map[Id[Library], Int]()
    val extra = mutable.Map[String, Int]()

    val collisions = journal.getVisited()
    log.info(s"Collisions found: ${collisions.groupBy(_._1.kind).mapValues(_.size).mkString(", ")}")

    collisions collect {
      case (vertexId, count) if count <= 1 || forbiddenCollisions.contains(vertexId) || (collisionFilter.nonEmpty && !collisionFilter.contains(vertexId.kind)) => // ignore
      case (vertexId, count) if vertexId.kind == UserReader => users += VertexDataId.toUserId(vertexId.asId[UserReader]) -> count
      case (vertexId, count) if vertexId.kind == UriReader => uris += VertexDataId.toNormalizedUriId(vertexId.asId[UriReader]) -> count
      case (vertexId, count) if vertexId.kind == FacebookAccountReader => socialUsers += VertexDataId.fromFacebookAccountIdtoSocialUserId(vertexId.asId[FacebookAccountReader]) -> count
      case (vertexId, count) if vertexId.kind == LinkedInAccountReader => socialUsers += VertexDataId.fromLinkedInAccountIdtoSocialUserId(vertexId.asId[LinkedInAccountReader]) -> count
      case (vertexId, count) if vertexId.kind == LibraryReader => libraries += VertexDataId.toLibraryId(vertexId.asId[LibraryReader]) -> count
      case (vertexId, count) => extra += vertexId.toString() -> count
    }
    Collisions(users.toMap, socialUsers.toMap, libraries.toMap, uris.toMap, extra.toMap)
  }

  private def getForbiddenCollisions(startingVertexId: VertexId, avoidTrivialCollisions: Boolean): Set[VertexId] = {
    val start = currentDateTime
    val forbiddenCollisions = if (!avoidTrivialCollisions) { Set(startingVertexId) }
    else graphManager.readOnly { reader =>
      val vertexReader = reader.getNewVertexReader()
      val collectNeighbors = GraphPrimitives.collectOutgoingNeighbors(vertexReader) _
      val firstDegree = collectNeighbors(startingVertexId, VertexKind.all)
      val forbiddenUris = if (startingVertexId.kind != UserReader) Set.empty else {
        firstDegree.collect { case keep if keep.kind == KeepReader => collectNeighbors(keep, Set(UriReader)) }.flatten
      }
      firstDegree ++ forbiddenUris + startingVertexId
    }

    val end = currentDateTime
    log.info(s"Resolved forbidden collisions in ${end.getMillis - start.getMillis} ms: ${forbiddenCollisions.groupBy(_.kind).mapValues(_.size).mkString(", ")}")
    forbiddenCollisions
  }

  def explainFeed() = Action.async(parse.json) { request =>
    val js = request.body
    val userId = (js \ "user").as[Id[User]]
    val uriIds = (js \ "uris").as[Seq[Id[NormalizedURI]]]
    feedExplanationCommander.explain(userId, uriIds).map { explain =>
      Ok(Json.toJson(explain))
    }
  }

  def uriWandering(userId: Id[User], steps: Int) = Action.async { request =>
    uriWanderingCommander.wander(userId, steps).map { res =>
      Ok(Json.toJson(res))
    }
  }
}
