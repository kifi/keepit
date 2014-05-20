package com.keepit.graph.wander

import com.google.inject.Inject
import com.keepit.graph.manager.GraphManager
import com.keepit.graph.model._
import com.keepit.model.{Keep, NormalizedURI, User}
import com.keepit.common.db.Id
import scala.collection.mutable
import com.keepit.graph.model.VertexKind.VertexType

class WanderingCommander @Inject() (graph: GraphManager) {

  def exploreFromUser(userId: Id[User], preferUsers: Boolean, preferUris: Boolean, steps: Int, restartProbability: Double): Recommendations = {
    val startingPoint = VertexId(userId)
    explore(startingPoint, preferUsers, preferUris, steps, restartProbability)
  }

  def exploreFromUri(uriId: Id[NormalizedURI], preferUsers: Boolean, preferUris: Boolean, steps: Int, restartProbability: Double): Recommendations = {
    val startingPoint = VertexId(uriId)
    explore(startingPoint, preferUsers, preferUris, steps, restartProbability)
  }

  def exploreFromKeep(keepId: Id[Keep], preferUsers: Boolean, preferUris: Boolean, steps: Int, restartProbability: Double): Recommendations = {
    val startingPoint = VertexId(keepId)
    explore(startingPoint, preferUsers, preferUris, steps, restartProbability)
  }

  def exploreFromTag(tagId: Id[User], preferUsers: Boolean, preferUris: Boolean, steps: Int, restartProbability: Double): Recommendations = {
    val startingPoint = VertexId(tagId)
    explore(startingPoint, preferUsers, preferUris, steps, restartProbability)
  }

  private def explore(startingPoint: VertexId, preferUsers: Boolean, preferUris: Boolean, steps: Int, restartProbability: Double): Recommendations = {
    val preferredOutputs = getPreferredDestinations(preferUsers, preferUris)
    val destinations = wander(startingPoint, preferredOutputs, steps, restartProbability)
    getRecommendations(destinations)
  }

  private def wander(startingPoint: VertexId, preferredDestinations: Set[VertexType], steps: Int, restartProbability: Double): Map[VertexId, Int] = {
    val journal = new DestinationJournal()
    graph.readOnly { reader =>
      val wanderer = reader.getNewVertexReader()
      val scout = reader.getNewVertexReader()
      val scoutingWanderer = new ScoutingWanderer(wanderer, scout)
      val teleporter = new UniformTeleporter(Set(startingPoint), preferredDestinations, restartProbability)
      val resolver = new RestrictedDestinationResolver(VertexKind.all)
      scoutingWanderer.wander(steps, teleporter, resolver, journal)
    }

    journal.getDestinations()
  }

  private def getRecommendations(destinations: Map[VertexId, Int]): Recommendations = {
    val users = mutable.Map[Id[User], Int]()
    val uris =  mutable.Map[Id[NormalizedURI], Int]()
    val extra = mutable.Map[String, Int]()
    destinations.foreach {
      case (vertexId, count) if vertexId.kind == UserReader => users += VertexDataId.toUserId(vertexId.asId[UserReader]) -> count
      case (vertexId, count) if vertexId.kind == UriReader => uris += VertexDataId.toNormalizedUriId(vertexId.asId[UriReader]) -> count
      case (vertexId, count) => extra += vertexId.toString() -> count
    }
    Recommendations(users.toMap, uris.toMap, extra.toMap)
  }

  private def getPreferredDestinations(preferUsers: Boolean, preferUris: Boolean): Set[VertexType] = {
    val preferredDestinations = mutable.Set[VertexType]()
    if (preferUsers) preferredDestinations += UserReader
    if (preferUris) preferredDestinations += UriReader
    preferredDestinations.toSet
  }
}
