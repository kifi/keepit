package com.keepit.graph.model

import org.neo4j.graphdb.Relationship
import com.keepit.graph.database.{NeoEdgeFactory, NeoEdge}

trait Edge[+S, +D, +T] {
  def sourceId: VertexId[S]
  def destinationId: VertexId[D]
  def data: T

  def source: Vertex[S]
  def destination: Vertex[D]
}

trait EdgeData

object EdgeData extends NeoEdgeFactory[VertexData, EdgeData] {

  def neoEdge(relationship: Relationship) = {
    relationship.getType.name().split(".").map(Companion.fromTypeCodeString(_)) match {
      case Array(UserData, UriData, KeptData) => NeoEdge[UserData, UriData, KeptData](relationship)
      case Array(UserData, CollectionData, CollectsData) => NeoEdge[UserData, CollectionData, CollectsData](relationship)
      case Array(CollectionData, UriData, ContainsData) => NeoEdge[CollectionData, UriData, ContainsData](relationship)
      case Array(UserData, UserData, FollowsData) => NeoEdge[UserData, UserData, FollowsData](relationship)
    }
  }

}





