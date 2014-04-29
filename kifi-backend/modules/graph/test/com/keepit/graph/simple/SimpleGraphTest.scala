package com.keepit.graph.simple

import org.specs2.mutable.Specification
import com.keepit.graph.model._

class SimpleGraphTest() extends Specification {
  val graph = SimpleGraph()

  val alfred = VertexDataId[UserReader](1899)
  val leo = VertexDataId[UserReader](134)
  val (vertigo, rearWindow) = (VertexDataId[UriReader](1958), VertexDataId[UriReader](1954))

  val graphReader = graph.getNewReader()
  val vertexReader = graphReader.getNewVertexReader()
  val edgeReader = graphReader.getNewEdgeReader()

  "SimpleGraph" should {
    "save and retrieve vertices" in {

      vertexReader.moveTo(alfred) must throwA[VertexNotFoundException]
      vertexReader.moveTo(vertigo) must throwA[VertexNotFoundException]

      graph.readWrite { writer =>
        writer.saveVertex(UserData(alfred))
        writer.saveVertex(UriData(vertigo))
      }

      vertexReader.moveTo(alfred)
      vertexReader.kind === UserReader
      vertexReader.data.id === alfred

      vertexReader.moveTo(vertigo)
      vertexReader.kind === UriReader
      vertexReader.data.id === vertigo
    }

    "save, retrieve and remove edges" in {
      vertexReader.moveTo(alfred)
      vertexReader.edgeReader.degree === 0
      vertexReader.moveTo(vertigo)
      vertexReader.edgeReader.degree === 0
      edgeReader.moveTo(alfred, vertigo) must throwA[EdgeNotFoundException]

      graph.readWrite { writer =>
        writer.saveEdge(alfred, vertigo, EmptyEdgeData)
      }

      vertexReader.moveTo(alfred)
      vertexReader.edgeReader.degree === 1
      vertexReader.edgeReader.moveToNextEdge()
      vertexReader.edgeReader.kind === EmptyEdgeDataReader
      edgeReader.moveTo(alfred, vertigo)
      edgeReader.kind === EmptyEdgeDataReader
      vertexReader.moveTo(vertigo)
      vertexReader.edgeReader.degree === 0

      graph.readWrite { writer =>
        writer.removeEdge(alfred, vertigo, EmptyEdgeDataReader)
      }

      vertexReader.moveTo(alfred)
      vertexReader.edgeReader.degree === 0
      edgeReader.moveTo(alfred, vertigo) must throwA[EdgeNotFoundException]
    }

    "not be modified until a writer commits new data" in {

      vertexReader.moveTo(rearWindow) must throwA[VertexNotFoundException]
      edgeReader.moveTo(alfred, rearWindow) must throwA[VertexNotFoundException]

      graph.readWrite { writer =>
        writer.saveVertex(UriData(rearWindow))
        writer.saveEdge(alfred, vertigo, EmptyEdgeData)
        writer.saveEdge(alfred, rearWindow, EmptyEdgeData)

        val dirtyVertexReader = writer.getNewVertexReader()

        dirtyVertexReader.moveTo(rearWindow)
        dirtyVertexReader.kind === UriReader
        dirtyVertexReader.data.id === rearWindow
        dirtyVertexReader.moveTo(alfred)
        dirtyVertexReader.edgeReader.degree === 2
        vertexReader.moveTo(rearWindow) must throwA[VertexNotFoundException]
        vertexReader.moveTo(alfred)
        vertexReader.edgeReader.degree === 0

        val dirtyEdgeReader = writer.getNewEdgeReader()

        dirtyEdgeReader.moveTo(alfred, rearWindow)
        dirtyEdgeReader.moveTo(alfred, vertigo)
        edgeReader.moveTo(alfred, vertigo) must throwA[EdgeNotFoundException]
        edgeReader.moveTo(alfred, rearWindow) must throwA[VertexNotFoundException]
      }

      vertexReader.moveTo(rearWindow)
      edgeReader.moveTo(alfred, rearWindow)
      edgeReader.moveTo(alfred, vertigo)

      "All good" === "All good"
    }

    "remove a vertex and its outgoing and incoming edges on commit" in {
      graph.readWrite { writer =>
        writer.saveVertex(UserData(leo))
        writer.saveEdge(leo, alfred, EmptyEdgeData)
        writer.saveEdge(alfred, leo, EmptyEdgeData)
      }

      vertexReader.moveTo(leo)
      edgeReader.moveTo(leo, alfred)
      edgeReader.moveTo(alfred, leo)

      graph.readWrite { writer =>
        writer.removeVertex(leo)

        val dirtyVertexReader = writer.getNewVertexReader()
        val dirtyEdgeReader = writer.getNewEdgeReader()
        dirtyVertexReader.moveTo(leo) must throwA[VertexNotFoundException]
        dirtyEdgeReader.moveTo(leo, alfred) must throwA[VertexNotFoundException]
        dirtyEdgeReader.moveTo(alfred, leo) must throwA[VertexNotFoundException]

        vertexReader.moveTo(leo)
        edgeReader.moveTo(leo, alfred)
        edgeReader.moveTo(alfred, leo)
      }

      vertexReader.moveTo(leo) must throwA[VertexNotFoundException]
      edgeReader.moveTo(leo, alfred) must throwA[VertexNotFoundException]
      edgeReader.moveTo(alfred, leo) must throwA[VertexNotFoundException]

      graph.readWrite { writer =>
        writer.saveVertex(UserData(leo))
      }

      vertexReader.moveTo(leo)
      edgeReader.moveTo(leo, alfred) must throwA[EdgeNotFoundException]
      edgeReader.moveTo(alfred, leo) must throwA[EdgeNotFoundException]
    }

    "be properly serialized and deserialized to Json" in {
      val json = SimpleGraph.format.writes(graph)
      val newGraph = SimpleGraph.format.reads(json).get
      val newGraphReader = newGraph.getNewReader()
      val newGraphVertexReader = newGraphReader.getNewVertexReader()
      newGraphVertexReader.moveTo(rearWindow)
      newGraphVertexReader.kind === UriReader
      newGraphVertexReader.data.id === rearWindow
      newGraphVertexReader.moveTo(alfred)
      newGraphVertexReader.edgeReader.degree === 2

      val newGraphEdgeReader = newGraphReader.getNewEdgeReader()

      newGraphEdgeReader.moveTo(alfred, rearWindow)
      newGraphEdgeReader.moveTo(alfred, vertigo)

      "All good" === "All good"
    }
  }
}
