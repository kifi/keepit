package com.keepit.graph.wander

import com.google.inject.{Singleton, Inject}
import com.keepit.graph.manager.GraphManager
import com.keepit.graph.model._
import com.keepit.model.{SocialUserInfo, NormalizedURI, User}
import com.keepit.common.db.Id
import scala.collection.mutable
import com.keepit.graph.model.VertexKind.VertexType
import com.keepit.common.logging.Logging
import com.keepit.common.time._

@Singleton
class WanderingCommander @Inject() (graph: GraphManager, clock: Clock) extends Logging {

  def wander(wanderlust: Wanderlust): Collisions = {
    log.info(s"Preparing to wander: $wanderlust")

    val startingVertexKind = VertexKind.apply(wanderlust.startingVertexKind)
    val startingVertexId = VertexId(startingVertexKind)(wanderlust.startingVertexDataId)
    val forbiddenCollisions = getForbiddenCollisions(startingVertexId, wanderlust.allowTrivialCollisions)
    val preferredCollisions = wanderlust.preferredCollisions.map(VertexKind(_))

    val teleporter = UniformTeleporter(Set(startingVertexId), wanderlust.restartProbability) { wanderer =>
      !forbiddenCollisions.contains(wanderer.id) && (preferredCollisions.isEmpty || preferredCollisions.contains(wanderer.kind))
    }

    val journal = new TeleportationJournal()

    val resolver = RestrictedDestinationResolver { case (source, destination, edge) =>
      !journal.getLastVisited().exists(_ == destination.id)
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
    getCollisions(journal)
  }

  private def getCollisions(journal: TeleportationJournal): Collisions = {
    val users = mutable.Map[Id[User], Int]()
    val socialUsers = mutable.Map[Id[SocialUserInfo], Int]()
    val uris =  mutable.Map[Id[NormalizedURI], Int]()
    val extra = mutable.Map[String, Int]()

    journal.getTeleportations().foreach {
      case (vertexId, count) if vertexId.kind == UserReader => users += VertexDataId.toUserId(vertexId.asId[UserReader]) -> count
      case (vertexId, count) if vertexId.kind == UriReader => uris += VertexDataId.toNormalizedUriId(vertexId.asId[UriReader]) -> count
      case (vertexId, count) if vertexId.kind == FacebookAccountReader => socialUsers += VertexDataId.fromFacebookAccountIdtoSocialUserId(vertexId.asId[FacebookAccountReader]) -> count
      case (vertexId, count) if vertexId.kind == LinkedInAccountReader => socialUsers += VertexDataId.fromLinkedInAccountIdtoSocialUserId(vertexId.asId[LinkedInAccountReader]) -> count
      case (vertexId, count) => extra += vertexId.toString() -> count
    }
    log.info(s"Collisions found: ${users.size} users, ${socialUsers.size} social users, ${uris.size} uris, ${extra.size} more.")
    Collisions(users.toMap, socialUsers.toMap, uris.toMap, extra.toMap)
  }

  private def collectNeighbors(vertexReader: GlobalVertexReader)(vertexId: VertexId,  neighborKinds: Set[VertexType]): Set[VertexId] = {
    vertexReader.moveTo(vertexId)
    val neighbors = mutable.Set[VertexId]()
    while (vertexReader.edgeReader.moveToNextEdge()) {
      val neighbor = vertexReader.edgeReader.destination
      if (neighborKinds.contains(neighbor.kind)) { neighbors += neighbor }
    }
    neighbors.toSet
  }

  private def getForbiddenCollisions(startingVertexId: VertexId, allowTrivialCollisions: Boolean): Set[VertexId] = {
    val start = clock.now()
    val forbiddenCollisions = if (allowTrivialCollisions) { Set(startingVertexId) }
    else graph.readOnly { reader =>
      val vertexReader = reader.getNewVertexReader()
      val firstDegree = collectNeighbors(vertexReader)(startingVertexId, VertexKind.all)
      val forbiddenUris = if (startingVertexId.kind != UserReader) Set.empty else {
        firstDegree.collect { case keep if keep.kind == KeepReader => collectNeighbors(vertexReader)(keep, Set(UriReader)) }.flatten
      }
      firstDegree ++ forbiddenUris + startingVertexId
    }

    val end = clock.now()
    log.info(s"Resolved forbidden collisions in ${end.getMillis - start.getMillis}ms: $forbiddenCollisions")
    forbiddenCollisions
  }
}
