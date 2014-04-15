package com.keepit.graph.simple

import org.specs2.mutable.Specification
import com.keepit.graph.model._

class SimpleGraphTest() extends Specification {
  val graph = SimpleGraph()

  val alfred = VertexDataId[UserReader](1899)
  val (vertigo, rearWindow) = (VertexDataId[UriReader](1958), VertexDataId[UriReader](1954))

  val vertexReader = graph.getNewVertexReader()
  val edgeReader = graph.getNewEdgeReader()

  "SimpleGraph" should {
    "save and retrieve vertices" in {

      vertexReader.moveTo(alfred) must throwA[VertexReaderException]
      vertexReader.moveTo(vertigo) must throwA[VertexReaderException]

      graph.write { writer =>
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
      edgeReader.moveTo(alfred, vertigo) must throwA[EdgeReaderException]

      graph.write { writer =>
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

      graph.write { writer =>
        writer.removeEdge(alfred, vertigo)
      }

      vertexReader.moveTo(alfred)
      vertexReader.edgeReader.degree === 0
      edgeReader.moveTo(alfred, vertigo) must throwA[EdgeReaderException]
    }
  }

  "not be modified until a writer commits new data" in {

    vertexReader.moveTo(rearWindow) must throwA[VertexReaderException]
    edgeReader.moveTo(alfred, rearWindow) must throwA[EdgeReaderException]

    graph.write { writer =>
      writer.saveVertex(UriData(rearWindow))
      writer.saveEdge(alfred, vertigo, EmptyEdgeData)
      writer.saveEdge(alfred, rearWindow, EmptyEdgeData)

      val dirtyVertexReader = writer.getNewVertexReader()

      dirtyVertexReader.moveTo(rearWindow)
      dirtyVertexReader.kind === UriReader
      dirtyVertexReader.data.id === rearWindow
      dirtyVertexReader.moveTo(alfred)
      dirtyVertexReader.edgeReader.degree === 2
      vertexReader.moveTo(rearWindow) must throwA[VertexReaderException]
      vertexReader.moveTo(alfred)
      vertexReader.edgeReader.degree === 0

      val dirtyEdgeReader = writer.getNewEdgeReader()

      dirtyEdgeReader.moveTo(alfred, rearWindow)
      dirtyEdgeReader.moveTo(alfred, vertigo)
      edgeReader.moveTo(alfred, vertigo) must throwA[EdgeReaderException]
      edgeReader.moveTo(alfred, rearWindow) must throwA[EdgeReaderException]
    }

    vertexReader.moveTo(rearWindow)
    edgeReader.moveTo(alfred, rearWindow)
    edgeReader.moveTo(alfred, vertigo)

    "All good" === "All good"
  }

  "be properly serialized and deserialized to Json" in {
    val json = SimpleGraph.format.writes(graph)
    val newGraph = SimpleGraph.format.reads(json).get

    val newGraphVertexReader = newGraph.getNewVertexReader()
    newGraphVertexReader.moveTo(rearWindow)
    newGraphVertexReader.kind === UriReader
    newGraphVertexReader.data.id === rearWindow
    newGraphVertexReader.moveTo(alfred)
    newGraphVertexReader.edgeReader.degree === 2

    val newGraphEdgeReader = newGraph.getNewEdgeReader()

    newGraphEdgeReader.moveTo(alfred, rearWindow)
    newGraphEdgeReader.moveTo(alfred, vertigo)

    "All good" === "All good"
  }
}
