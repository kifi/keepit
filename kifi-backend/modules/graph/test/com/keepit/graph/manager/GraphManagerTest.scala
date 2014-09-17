package com.keepit.graph.manager

import com.keepit.graph.GraphTestHelper
import com.keepit.graph.model._
import com.keepit.graph.simple.{ SimpleGraphTestModule }
import com.keepit.graph.test.GraphTestInjector
import com.keepit.graph.wander.NeighborQuerier
import org.specs2.mutable.Specification

class GraphManagerTest extends Specification with GraphTestInjector with GraphTestHelper with NeighborQuerier {
  "grap ingestion" should {
    "correctly ingest data" in {
      withInjector(SimpleGraphTestModule()) { implicit injector =>
        val manager = inject[GraphManager]
        manager.update(allUpdates: _*)

        manager.readOnly { reader =>
          val v = reader.getNewVertexReader()
          v.moveTo(VertexDataId[UserReader](1))

          // user connections
          var nbs = getNeighbors(v, (UserReader, UserReader, EmptyEdgeReader), true)
          nbs.map { x: VertexId => x.asId[UserReader].id } === Set(42)

          v.moveTo(VertexDataId[UserReader](42))
          nbs = getNeighbors(v, (UserReader, UserReader, EmptyEdgeReader), true)
          nbs.map { x: VertexId => x.asId[UserReader].id } === Set(1, 2, 3, 43)

          // user keeps
          nbs = getNeighbors(v, (UserReader, KeepReader, TimestampEdgeReader), true)
          nbs.map { x: VertexId => x.asId[KeepReader].id } === Set(5)

          v.moveTo(VertexDataId[UserReader](43))
          nbs = getNeighbors(v, (UserReader, KeepReader, TimestampEdgeReader), true)
          nbs.map { x: VertexId => x.asId[KeepReader].id } === Set(1, 2, 3, 4)

          // user libraries
          v.moveTo(VertexDataId[UserReader](1))
          nbs = getNeighbors(v, (UserReader, LibraryReader, EmptyEdgeReader), true)
          nbs.map { x: VertexId => x.asId[LibraryReader].id } === Set(1, 2)

          // library to users
          v.moveTo(VertexDataId[LibraryReader](1))
          nbs = getNeighbors(v, (LibraryReader, UserReader, EmptyEdgeReader), true)
          nbs.map { x: VertexId => x.asId[UserReader].id } === Set(1, 2)

          // library to keeps
          nbs = getNeighbors(v, (LibraryReader, KeepReader, EmptyEdgeReader), true)
          nbs.map { x: VertexId => x.asId[KeepReader].id } === Set(1, 2, 3, 4, 5)

          // keep to uris
          v.moveTo(VertexDataId[KeepReader](1))
          nbs = getNeighbors(v, (KeepReader, UriReader, EmptyEdgeReader), true)
          nbs.map { x: VertexId => x.asId[UriReader].id } === Set(1)
        }

      }
    }
  }

}
