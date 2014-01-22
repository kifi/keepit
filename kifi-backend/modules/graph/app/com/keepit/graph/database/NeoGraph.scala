package com.keepit.graph.database

import com.keepit.graph.model._
import com.google.inject.Inject
import org.neo4j.graphdb.{Node, Relationship, DynamicRelationshipType, GraphDatabaseService}

class NeoGraph[V, E] @Inject() (graphDb: GraphDatabaseService, vertexFactory: NeoVertexFactory[V], edgeFactory: NeoEdgeFactory[V, E])
  extends Graph[V, E] with NeoVertexFactory[V] with NeoEdgeFactory[V, E] {

  def neoEdge(relationship: Relationship): NeoEdge[V, V, E] = edgeFactory.neoEdge(relationship)
  def neoVertex(node: Node): NeoVertex[V] = vertexFactory.neoVertex(node)

  private def withinTransaction[T](block: => T): T = {
    val tx = graphDb.beginTx()
    try {
      val result = block
      tx.success()
      result
    }
    finally {
      tx.finish()
    }
  }

  def createVertices[T <: V : Companion](vertices: (VertexId[T], T)*) = withinTransaction {
    val u = implicitly[Companion[T]]
    vertices.map { case (id, data) =>
      val node = graphDb.createNode( )
      node.setProperty("type", u.typeCode.toString())
      node.setProperty("id", id.id)
      node.setProperty("data", u.format.writes(data).toString())
      NeoVertex[T](node)
    }
  }

  def getVertices[T <: V : Companion](vertexIds: VertexId[T]*): Seq[NeoVertex[T]] = ???

  def createEdges[S <: V : Companion, D <: V : Companion, T <: E : Companion](edges: (VertexId[S], VertexId[D], T)*): Seq[Edge[S, D, T]] = {
    val sources = getVertices[S](edges.map(_._1): _*)
    val destinations = getVertices[D](edges.map(_._2): _*)
    val data = edges.map(_._3)
    val edgesWithVertices = (sources zip destinations zip data).map {case ((s, d), e) => (s, d, e) }
    createEdges(edgesWithVertices: _*)
  }

  def createEdges[S <: V : Companion, D <: V : Companion, T <: E : Companion](edges: (Vertex[S], Vertex[D], T)*): Seq[Edge[S, D, T]] = withinTransaction {

    val s = implicitly[Companion[S]]
    val d = implicitly[Companion[D]]
    val f = implicitly[Companion[T]]
    val relationshipName = Seq(s, d ,f).map(_.typeCode.toString).mkString(".")
    val relationshipType = DynamicRelationshipType.withName(relationshipName)

    edges.map { case (NeoVertex(sourceNode), NeoVertex(destinationNode), data) =>

      val relationship = sourceNode.createRelationshipTo(destinationNode, relationshipType)
      relationship.setProperty("sourceId", sourceNode.getProperty("id"))
      relationship.setProperty("destinationId", destinationNode.getProperty("id"))
      relationship.setProperty("data", f.format.writes(data).toString())
      NeoEdge[S, D, T](relationship)
    }
  }

}
