package com.keepit.graph.simple

import org.specs2.mutable.Specification
import com.keepit.graph.model._
import java.io.File
import org.apache.commons.io.FileUtils

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
      vertexReader.outgoingEdgeReader.degree must throwA[UninitializedReaderException]
      vertexReader.outgoingEdgeReader.moveToNextComponent() === false
      vertexReader.moveTo(vertigo)
      vertexReader.outgoingEdgeReader.moveToNextComponent() === false
      edgeReader.moveTo(alfred, vertigo, EmptyEdgeReader) must throwA[EdgeNotFoundException]

      graph.readWrite { writer =>
        writer.saveEdge(alfred, vertigo, EmptyEdgeData)
      }

      vertexReader.moveTo(alfred)
      vertexReader.outgoingEdgeReader.moveToNextEdge() must throwA[UninitializedReaderException]
      vertexReader.outgoingEdgeReader.moveToNextComponent()
      vertexReader.outgoingEdgeReader.degree === 1
      vertexReader.outgoingEdgeReader.moveToNextEdge()
      vertexReader.outgoingEdgeReader.kind === EmptyEdgeReader
      edgeReader.moveTo(alfred, vertigo, EmptyEdgeReader)
      edgeReader.kind === EmptyEdgeReader
      vertexReader.moveTo(vertigo)
      vertexReader.outgoingEdgeReader.moveToNextComponent() === false

      graph.readWrite { writer =>
        writer.removeEdge(alfred, vertigo, EmptyEdgeReader)
      }

      vertexReader.moveTo(alfred)
      vertexReader.outgoingEdgeReader.moveToNextComponent() === false
      edgeReader.moveTo(alfred, vertigo, EmptyEdgeReader) must throwA[EdgeNotFoundException]
    }

    "not be modified until a writer commits new data" in {

      vertexReader.moveTo(rearWindow) must throwA[VertexNotFoundException]
      edgeReader.moveTo(alfred, rearWindow, EmptyEdgeReader) must throwA[VertexNotFoundException]

      graph.readWrite { writer =>
        writer.saveVertex(UriData(rearWindow))
        writer.saveEdge(alfred, vertigo, EmptyEdgeData)
        writer.saveEdge(alfred, rearWindow, EmptyEdgeData)

        val dirtyVertexReader = writer.getNewVertexReader()

        dirtyVertexReader.moveTo(rearWindow)
        dirtyVertexReader.kind === UriReader
        dirtyVertexReader.data.id === rearWindow
        dirtyVertexReader.moveTo(alfred)
        dirtyVertexReader.outgoingEdgeReader.moveToNextComponent()
        dirtyVertexReader.outgoingEdgeReader.degree === 2
        vertexReader.moveTo(rearWindow) must throwA[VertexNotFoundException]
        vertexReader.moveTo(alfred)
        vertexReader.outgoingEdgeReader.moveToNextComponent() === false

        val dirtyEdgeReader = writer.getNewEdgeReader()

        dirtyEdgeReader.moveTo(alfred, rearWindow, EmptyEdgeReader)
        dirtyEdgeReader.moveTo(alfred, vertigo, EmptyEdgeReader)
        edgeReader.moveTo(alfred, vertigo, EmptyEdgeReader) must throwA[EdgeNotFoundException]
        edgeReader.moveTo(alfred, rearWindow, EmptyEdgeReader) must throwA[VertexNotFoundException]
      }

      vertexReader.moveTo(rearWindow)
      edgeReader.moveTo(alfred, rearWindow, EmptyEdgeReader)
      edgeReader.moveTo(alfred, vertigo, EmptyEdgeReader)

      "All good" === "All good"
    }

    "remove a vertex and its outgoing and incoming edges on commit" in {
      graph.readWrite { writer =>
        writer.saveVertex(UserData(leo))
        writer.saveEdge(leo, alfred, EmptyEdgeData)
        writer.saveEdge(alfred, leo, EmptyEdgeData)
      }

      vertexReader.moveTo(leo)
      edgeReader.moveTo(leo, alfred, EmptyEdgeReader)
      edgeReader.moveTo(alfred, leo, EmptyEdgeReader)

      graph.readWrite { writer =>
        writer.removeVertex(leo)

        val dirtyVertexReader = writer.getNewVertexReader()
        val dirtyEdgeReader = writer.getNewEdgeReader()
        dirtyVertexReader.moveTo(leo) must throwA[VertexNotFoundException]
        dirtyEdgeReader.moveTo(leo, alfred, EmptyEdgeReader) must throwA[VertexNotFoundException]
        dirtyEdgeReader.moveTo(alfred, leo, EmptyEdgeReader) must throwA[VertexNotFoundException]

        vertexReader.moveTo(leo)
        edgeReader.moveTo(leo, alfred, EmptyEdgeReader)
        edgeReader.moveTo(alfred, leo, EmptyEdgeReader)
      }

      vertexReader.moveTo(leo) must throwA[VertexNotFoundException]
      edgeReader.moveTo(leo, alfred, EmptyEdgeReader) must throwA[VertexNotFoundException]
      edgeReader.moveTo(alfred, leo, EmptyEdgeReader) must throwA[VertexNotFoundException]

      graph.readWrite { writer =>
        writer.saveVertex(UserData(leo))
      }

      vertexReader.moveTo(leo)
      edgeReader.moveTo(leo, alfred, EmptyEdgeReader) must throwA[EdgeNotFoundException]
      edgeReader.moveTo(alfred, leo, EmptyEdgeReader) must throwA[EdgeNotFoundException]
    }

    "be properly serialized and deserialized to file" in {
      val tempFile = FileUtils.getFile(FileUtils.getTempDirectory, "simpleGraphTest")
      tempFile.deleteOnExit()
      SimpleGraph.write(graph, tempFile)
      val newGraph = SimpleGraph.read(tempFile)
      val newGraphReader = newGraph.getNewReader()
      val newGraphVertexReader = newGraphReader.getNewVertexReader()
      newGraphVertexReader.moveTo(rearWindow)
      newGraphVertexReader.kind === UriReader
      newGraphVertexReader.data.id === rearWindow
      newGraphVertexReader.moveTo(alfred)
      newGraphVertexReader.outgoingEdgeReader.moveToNextComponent()
      newGraphVertexReader.outgoingEdgeReader.degree === 2
      newGraphVertexReader.moveTo(leo)
      newGraphVertexReader.outgoingEdgeReader.moveToNextComponent() === false

      val newGraphEdgeReader = newGraphReader.getNewEdgeReader()

      newGraphEdgeReader.moveTo(alfred, rearWindow, EmptyEdgeReader)
      newGraphEdgeReader.moveTo(alfred, vertigo, EmptyEdgeReader)

      "All good" === "All good"
    }
  }
}
