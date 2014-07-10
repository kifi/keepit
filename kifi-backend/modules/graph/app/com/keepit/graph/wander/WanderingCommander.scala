package com.keepit.graph.wander

import com.google.inject.{ Singleton, Inject }
import com.keepit.graph.manager.GraphManager
import com.keepit.graph.model._
import com.keepit.model.{ URISummary, SocialUserInfo, NormalizedURI, User }
import com.keepit.common.db.Id
import scala.collection.mutable
import com.keepit.graph.model.VertexKind.VertexType
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.social.BasicUser

@Singleton
class WanderingCommander @Inject() (graph: GraphManager, clock: Clock) extends Logging {

  def wander(wanderlust: Wanderlust): Collisions = {
    log.info(s"Preparing to wander: $wanderlust")

    val startingVertexKind = VertexKind.apply(wanderlust.startingVertexKind)
    val startingVertexId = VertexId(startingVertexKind)(wanderlust.startingVertexDataId)

    val teleporter = UniformTeleporter(Set(startingVertexId)) { Function.const(wanderlust.restartProbability) }

    val journal = new TeleportationJournal()

    val resolver = {
      val now = clock.now().getMillis
      val from = wanderlust.recency.map(now - _.toMillis).getOrElse(0L)
      val tauOption: Option[Double] = wanderlust.halfLife.map(_.toMillis)
      val decay: TimestampEdgeReader => Double = {
        case outdatedEdge: TimestampEdgeReader if (outdatedEdge.timestamp < from) => 0
        case decayingEdge: TimestampEdgeReader => tauOption.map(tau => Math.exp(-(now - decayingEdge.timestamp) / tau)) getOrElse 1.0
      }

      val mayTraverse: (VertexReader, VertexReader, EdgeReader) => Boolean = {
        case (source, destination, edge) => !journal.getLastVisited().exists(_ == destination.id)
      }

      RestrictedDestinationResolver(mayTraverse, decay)
    }

    val start = clock.now()
    graph.readOnly { reader =>
      val wanderer = reader.getNewVertexReader()
      val scout = reader.getNewVertexReader()
      val scoutingWanderer = new ScoutingWanderer(wanderer, scout)
      scoutingWanderer.wander(wanderlust.steps, teleporter, resolver, journal)
    }
    val end = clock.now()
    log.info(s"Wandered for ${wanderlust.steps} steps during ${end.getMillis - start.getMillis} ms.")
    val forbiddenCollisions = getForbiddenCollisions(startingVertexId, wanderlust.avoidTrivialCollisions)
    val preferredCollisions = wanderlust.preferredCollisions.map(VertexKind(_))
    getCollisions(journal, forbiddenCollisions, preferredCollisions)
  }

  private def getCollisions(journal: TeleportationJournal, forbiddenCollisions: Set[VertexId], preferredCollisions: Set[VertexType]): Collisions = {

    val users = mutable.Map[Id[User], Int]()
    val socialUsers = mutable.Map[Id[SocialUserInfo], Int]()
    val uris = mutable.Map[Id[NormalizedURI], Int]()
    val extra = mutable.Map[String, Int]()

    val collisions = journal.getVisited()
    log.info(s"Collisions found: ${collisions.groupBy(_._1.kind).mapValues(_.size).mkString(", ")}")

    collisions collect {
      case (vertexId, count) if count <= 1 || forbiddenCollisions.contains(vertexId) || (preferredCollisions.nonEmpty && !preferredCollisions.contains(vertexId.kind)) => // ignore
      case (vertexId, count) if vertexId.kind == UserReader => users += VertexDataId.toUserId(vertexId.asId[UserReader]) -> count
      case (vertexId, count) if vertexId.kind == UriReader => uris += VertexDataId.toNormalizedUriId(vertexId.asId[UriReader]) -> count
      case (vertexId, count) if vertexId.kind == FacebookAccountReader => socialUsers += VertexDataId.fromFacebookAccountIdtoSocialUserId(vertexId.asId[FacebookAccountReader]) -> count
      case (vertexId, count) if vertexId.kind == LinkedInAccountReader => socialUsers += VertexDataId.fromLinkedInAccountIdtoSocialUserId(vertexId.asId[LinkedInAccountReader]) -> count
      case (vertexId, count) => extra += vertexId.toString() -> count
    }
    Collisions(users.toMap, socialUsers.toMap, uris.toMap, extra.toMap)
  }

  private def collectNeighbors(vertexReader: GlobalVertexReader)(vertexId: VertexId, neighborKinds: Set[VertexType]): Set[VertexId] = {
    vertexReader.moveTo(vertexId)
    val neighbors = mutable.Set[VertexId]()
    while (vertexReader.edgeReader.moveToNextComponent()) {
      val (destinationKind, _) = vertexReader.edgeReader.component
      if (neighborKinds.contains(destinationKind)) {
        while (vertexReader.edgeReader.moveToNextEdge()) { neighbors += vertexReader.edgeReader.destination }
      }
    }
    neighbors.toSet
  }

  private def getForbiddenCollisions(startingVertexId: VertexId, avoidTrivialCollisions: Boolean): Set[VertexId] = {
    val start = clock.now()
    val forbiddenCollisions = if (!avoidTrivialCollisions) { Set(startingVertexId) }
    else graph.readOnly { reader =>
      val vertexReader = reader.getNewVertexReader()
      val firstDegree = collectNeighbors(vertexReader)(startingVertexId, VertexKind.all)
      val forbiddenUris = if (startingVertexId.kind != UserReader) Set.empty else {
        firstDegree.collect { case keep if keep.kind == KeepReader => collectNeighbors(vertexReader)(keep, Set(UriReader)) }.flatten
      }
      firstDegree ++ forbiddenUris + startingVertexId
    }

    val end = clock.now()
    log.info(s"Resolved forbidden collisions in ${end.getMillis - start.getMillis} ms: ${forbiddenCollisions.groupBy(_.kind).mapValues(_.size).mkString(", ")}")
    forbiddenCollisions
  }
}
