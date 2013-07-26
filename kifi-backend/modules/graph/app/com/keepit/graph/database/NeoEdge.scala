package com.keepit.graph.database

import org.neo4j.graphdb.Relationship
import com.keepit.graph.model.Edge

case class NeoEdge[+S, +D, +E](relationship: Relationship) extends Edge[S, D, E] {
  def source = NeoVertex[S](relationship.getStartNode())
  def destination = NeoVertex[D](relationship.getEndNode())
  def data = relationship.getProperty("data").asInstanceOf[E]
}
