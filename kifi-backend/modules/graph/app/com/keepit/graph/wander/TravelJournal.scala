package com.keepit.graph.wander

import com.keepit.graph.model.{ VertexId, VertexReader }
import scala.collection.mutable
import com.keepit.graph.model.EdgeKind.EdgeType
import com.keepit.common.logging.Logging
import org.joda.time.DateTime
import com.keepit.common.time._
import play.api.Logger
import scala.collection.concurrent.TrieMap

trait TravelJournal {
  def onStart(start: VertexReader): Unit
  def onEdgeTraversal(source: VertexReader, destination: VertexReader, edgeKind: EdgeType): Unit
  def onTeleportation(source: VertexReader, destination: VertexReader): Unit
  def onDeadend(deadend: VertexReader, restart: VertexReader): Unit
  def onComplete(end: VertexReader): Unit
}

class TeleportationJournal(name: String, clock: Clock) extends TravelJournal {
  private val randomTeleportations = new TrieMap[VertexId, Int]().withDefaultValue(0)
  private val visited = new TrieMap[VertexId, Int]().withDefaultValue(0)
  private var lastVisited: Option[VertexId] = None
  private var startingVertexId: Option[VertexId] = None
  private var startTime: Option[DateTime] = None
  private var steps = 0

  private val log = Logger(name)

  def onStart(start: VertexReader): Unit = {
    startingVertexId = Some(start.id)
    startTime = Some(clock.now())
    log.debug(s"[Start] ${start.id}")
  }

  def onEdgeTraversal(source: VertexReader, destination: VertexReader, edgeKind: EdgeType) = {
    lastVisited = Some(source.id)
    visited(source.id) = visited(source.id) + 1
    steps += 1
    log.debug(s"[Traverse] ${source.id} --> ${destination.id} | ${edgeKind.code}")
  }

  def onTeleportation(source: VertexReader, destination: VertexReader) = {
    randomTeleportations(source.id) = randomTeleportations(source.id) + 1
    lastVisited = Some(source.id)
    visited(source.id) = visited(source.id) + 1
    steps += 1
    log.debug(s"[Teleportation] ${source.id} --> ${destination.id}")
  }

  def onDeadend(deadend: VertexReader, restart: VertexReader): Unit = {
    lastVisited = Some(deadend.id)
    visited(deadend.id) = visited(deadend.id) + 1
    steps += 1
    log.debug(s"[Deadend] ${deadend.id} --> ${restart.id}")
  }

  def onComplete(end: VertexReader): Unit = {
    val endTime = clock.now()
    log.debug(s"[Complete] ${end.id}")
    log.info(s"Wandered from ${getStartingVertex()} for ${steps} steps during ${endTime.getMillis - startTime.get.getMillis} ms.")
  }

  def getTeleportations(): Map[VertexId, Int] = randomTeleportations.toMap
  def getLastVisited(): Option[VertexId] = lastVisited
  def getVisited(): Map[VertexId, Int] = visited.toMap
  def getStartingVertex(): VertexId = startingVertexId getOrElse { throw new IllegalStateException("This journal has not been completed yet.") }
  def getCompletedSteps(): Int = steps
}
