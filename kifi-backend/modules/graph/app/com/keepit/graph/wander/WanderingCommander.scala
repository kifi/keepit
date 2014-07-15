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

  def wander(wanderlust: Wanderlust): TeleportationJournal = {
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
    journal
  }
}
