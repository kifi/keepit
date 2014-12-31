package com.keepit.graph.wander

import com.google.inject.Inject
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.math.{ ProbabilityDensityBuilder, ProbabilityDensity }
import com.keepit.common.time._

import com.keepit.graph.manager.GraphManager
import com.keepit.graph.model.EdgeKind._
import com.keepit.graph.model._
import com.keepit.model.{ User, NormalizedURI }
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class URIWanderingCommander @Inject() (
    graph: GraphManager,
    wanderingCommander: WanderingCommander) extends DestinationWeightsQuerier with Logging {

  val uriWanderLock = new ReactiveLock(5)

  def wander(user: Id[User], trials: Int): Future[Map[Id[NormalizedURI], Int]] = {
    uriWanderLock.withLockFuture {
      val (preScoreTrials, additionalTrials) = ((0.2f * trials).toInt, (0.8f * trials).toInt)
      preScore(user, preScoreTrials).map { preScoreResult =>
        if (preScoreResult.isEmpty()) {
          Map()
        } else {
          val trialsQuota = distributeTrialsQuota(preScoreResult, additionalTrials)

          val uriScores = mutable.Map[Id[NormalizedURI], Int]().withDefaultValue(0)
          preScoreResult.uriScore.foreach {
            case (uri, s) =>
              val uriId = VertexDataId.toNormalizedUriId(uri)
              uriScores(uriId) += s
          }
          sampleURIs(trialsQuota.user)(sampleURIsfromUser) foreach { case (uriId, s) => uriScores(uriId) += s }
          sampleURIs(trialsQuota.topic)(sampleURIsfromTopic) foreach { case (uriId, s) => uriScores(uriId) += s }

          uriScores.toMap
        }
      }
    }
  }

  private case class PreScoreResult(uriScore: Map[VertexDataId[UriReader], Int], userScore: Map[VertexDataId[UserReader], Int], topicScore: Map[VertexDataId[LDATopicReader], Int]) {
    def isEmpty(): Boolean = userScore.isEmpty && topicScore.isEmpty
  }

  private case class TrialsQuota(user: Map[VertexDataId[UserReader], Int], topic: Map[VertexDataId[LDATopicReader], Int])

  private def preScore(user: Id[User], trials: Int): Future[PreScoreResult] = {
    val wanderLust = Wanderlust.discovery(user).copy(steps = trials)
    wanderingCommander.wander(wanderLust).map { journalOpt =>
      val uriScores = new ListBuffer[(VertexDataId[UriReader], Int)]()
      val userScores = new ListBuffer[(VertexDataId[UserReader], Int)]()
      val topicScores = new ListBuffer[(VertexDataId[LDATopicReader], Int)]()

      if (journalOpt.isDefined) {
        journalOpt.get.getVisited().foreach {
          case (id, score) if id.kind == UriReader.kind && score > 1 =>
            val uriId = id.asId[UriReader]
            uriScores += uriId -> score
          case (id, score) if id.kind == UserReader.kind && score > 1 =>
            val userId = id.asId[UserReader]
            if (VertexDataId.toUserId(userId) != user) {
              userScores += userId -> score
            }
          case (id, score) if id.kind == LDATopicReader.kind && score > 1 =>
            val topicId = id.asId[LDATopicReader]
            topicScores += topicId -> score
          case _ =>
        }
      }

      PreScoreResult(uriScores.toMap, userScores.toMap, topicScores.toMap)
    }
  }

  // each node has a sampling quota, proportionally to the probability estimated by preScore
  private def distributeTrialsQuota(preScoreResult: PreScoreResult, totalTrials: Int): TrialsQuota = {
    val (userSum, topicSum) = (preScoreResult.userScore.values.sum.toFloat, preScoreResult.topicScore.values.sum.toFloat)
    val (adjUserSum, adjTopicSum) = (userSum, topicSum * 2) // adjusted sums, compensate to the fact that topic vertexes are naturally harder to reach from a user (given current graph structure)
    val z = adjUserSum + adjTopicSum
    val (userQuota, topicQuota) = (totalTrials * (adjUserSum / z), totalTrials * (adjTopicSum / z))
    val userTrials = preScoreResult.userScore.map { case (userId, s) => (userId, (userQuota * (s / userSum)).toInt) }
    val topicTrials = preScoreResult.topicScore.map { case (topicId, s) => (topicId, (topicQuota * (s / topicSum)).toInt) }
    log.info(s"users have quota: ${userQuota}, topics have quota: ${topicQuota}")
    TrialsQuota(userTrials, topicTrials)
  }

  private def sampleURIs[T <: VertexDataReader](trials: Map[VertexDataId[T], Int])(sampler: (VertexDataId[T], Int) => Map[Id[NormalizedURI], Int]) = {
    val score = mutable.Map[Id[NormalizedURI], Int]().withDefaultValue(0)
    trials.foreach {
      case (vertex, numTrials) =>
        sampler(vertex, numTrials) foreach { case (uriId, s) => score(uriId) += s }
    }
    score
  }

  private def sampleDestinations[S <: VertexDataReader: VertexKind, D <: VertexDataReader: VertexKind](source: VertexDataId[S], component: (VertexKind[S], VertexKind[D], _ <: EdgeType), resolver: EdgeResolver, trials: Int): Map[VertexDataId[D], Int] = {
    graph.readOnly { reader =>
      val wanderer = reader.getNewVertexReader()
      val scout = reader.getNewVertexReader()
      wanderer.moveTo(source)

      val builder = new ProbabilityDensityBuilder[VertexId]
      getDestinationWeights(wanderer, scout, Component(component._1, component._2, component._3), edgeResolver) { (vertexId, weight) => builder.add(vertexId, weight) }
      val density = builder.build()
      val scores = mutable.Map[VertexDataId[D], Int]().withDefaultValue(0)
      var i = 0
      while (i < trials) {
        density.sample(Math.random()) foreach { vertex => scores(vertex.asId[D]) += 1 }
        i += 1
      }
      scores.toMap
    }
  }

  private def sampleURIsfromUser(user: VertexDataId[UserReader], trials: Int): Map[Id[NormalizedURI], Int] = {
    val keepScores = sampleDestinations(user, (UserReader, KeepReader, TimestampEdgeReader), edgeResolver, trials)
    val uriScores = keepToURI(keepScores.keys.toSeq) map { case (keep, uri) => (VertexDataId.toNormalizedUriId(uri), keepScores(keep)) }
    uriScores.toMap
  }

  private def sampleURIsfromTopic(topic: VertexDataId[LDATopicReader], trials: Int): Map[Id[NormalizedURI], Int] = {
    val uriScores = sampleDestinations(topic, (LDATopicReader, UriReader, WeightedEdgeReader), edgeResolver, trials)
    uriScores.map { case (uri, s) => (VertexDataId.toNormalizedUriId(uri), s) }.toMap
  }

  private def keepToURI(keeps: Seq[VertexDataId[KeepReader]]): Map[VertexDataId[KeepReader], VertexDataId[UriReader]] = {
    val resolver = edgeResolver
    val keepToURIMap = mutable.Map.empty[VertexDataId[KeepReader], VertexDataId[UriReader]]
    keeps.foreach { keep =>
      sampleDestinations(keep, (KeepReader, UriReader, EmptyEdgeReader), resolver, 1).keysIterator.foreach { uri => keepToURIMap(keep) = uri }
    }
    keepToURIMap.toMap
  }

  private def edgeResolver = {
    val now = currentDateTime.getMillis
    val recency = 120 days
    val halfLife = 14 days
    val from = now - recency.toMillis
    val mayTraverse: (VertexReader, VertexReader, EdgeReader) => Boolean = { case _ => true }
    val decay: TimestampEdgeReader => Double = {
      case outdatedEdge: TimestampEdgeReader if (outdatedEdge.timestamp < from) => 0
      case decayingEdge: TimestampEdgeReader => Math.exp(-(now - decayingEdge.timestamp) / halfLife.toMillis.toDouble)
    }
    RestrictedDestinationResolver(None, mayTraverse, decay)
  }
}
