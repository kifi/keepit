package com.keepit.graph.wander

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.graph.manager.GraphManager
import com.keepit.graph.model._
import com.keepit.model.{ Keep, User, NormalizedURI }
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

@Singleton
class FeedExplanationCommander @Inject() (graph: GraphManager, clock: Clock) extends Logging {

  val lock = new ReactiveLock(5)
  val MIN_SCORE = 2

  def explain(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[GraphFeedExplanation]] = {
    lock.withLock {
      uriIds.map { uriId =>
        val journal = wander(userId, uriId)
        getFeedExplanation(journal)
      }
    }
  }

  private def getFeedExplanation(journal: TeleportationJournal): GraphFeedExplanation = {
    val uriScores = new ListBuffer[(Id[NormalizedURI], Int)]()
    val keepScores = new ListBuffer[(Id[Keep], Int)]()
    journal.getVisited().foreach {
      case (id, score) if id.kind == UriReader.kind =>
        val uriId = VertexDataId.toNormalizedUriId(id.asId[UriReader])
        uriScores += uriId -> score
      case (id, score) if id.kind == KeepReader.kind =>
        val keepId = VertexDataId.toKeepId(id.asId[KeepReader])
        keepScores += keepId -> score
      case _ =>
    }

    val m1 = keepScores.filter { case (_, score) => score >= MIN_SCORE }.toMap
    val m2 = uriScores.filter { case (_, score) => score >= MIN_SCORE }.toMap
    GraphFeedExplanation(m1, m2)
  }

  private def wander(userId: Id[User], uriId: Id[NormalizedURI]): TeleportationJournal = {

    log.info(s"explain feed for user ${userId}, uri: ${uriId}")

    val wanderlust = FeedExplanationWanderLust.wander(userId)
    val startingVertexKind = VertexKind.apply(wanderlust.startingVertexKind)
    val startingVertexId = VertexId(startingVertexKind)(wanderlust.startingVertexDataId)

    val uriVertexKind = VertexKind.apply("Uri")
    val uriVertexId = VertexId(uriVertexKind)(uriId.id)

    val teleporter = UniformTeleporter(Set(startingVertexId, uriVertexId)) { Function.const(wanderlust.restartProbability) }

    val journal = new TeleportationJournal("FeedExplanationJournal", clock)

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

      RestrictedDestinationResolver(Some(FeedExplanationWanderLust.subgraph), mayTraverse, decay)
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

object FeedExplanationWanderLust {

  def wander(userId: Id[User]) =
    Wanderlust(
      startingVertexKind = "User",
      startingVertexDataId = userId.id,
      preferredCollisions = Set(),
      avoidTrivialCollisions = true,
      steps = 2500,
      restartProbability = 0.15,
      recency = Some(120 days),
      halfLife = Some(7 days)
    )

  val subgraph = Set(
    // kifi network
    Component(UserReader, UserReader, EmptyEdgeReader),

    Component(UserReader, KeepReader, TimestampEdgeReader),
    Component(KeepReader, UserReader, EmptyEdgeReader),

    Component(UriReader, KeepReader, TimestampEdgeReader),
    Component(KeepReader, UriReader, EmptyEdgeReader),

    Component(UriReader, LDATopicReader, WeightedEdgeReader),
    Component(LDATopicReader, UriReader, WeightedEdgeReader)
  )
}
