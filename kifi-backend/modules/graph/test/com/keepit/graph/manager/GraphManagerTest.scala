package com.keepit.graph.manager

import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.service.IpAddress
import com.keepit.graph.GraphTestHelper
import com.keepit.graph.model._
import com.keepit.graph.simple.{ SimpleGraphTestModule }
import com.keepit.graph.test.GraphTestInjector
import com.keepit.graph.utils.NeighborQuerier
import com.keepit.model.{ LibraryStates, Library }
import org.specs2.mutable.Specification

class GraphManagerTest extends Specification with GraphTestInjector with GraphTestHelper with NeighborQuerier {
  "graph ingestion" should {
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

          // user to org
          nbs = getNeighbors(v, (UserReader, OrganizationReader, TimestampEdgeReader), true)
          nbs.map { x: VertexId => x.asId[OrganizationReader].id } === Set(1)

          // org to users
          v.moveTo(VertexDataId[OrganizationReader](1))
          nbs = getNeighbors(v, (OrganizationReader, UserReader, TimestampEdgeReader), true)

          // user to ip address
          nbs = getNeighbors(v, (UserReader, IpAddressReader, TimestampEdgeReader), true)
          nbs.map { x: VertexId => x.asId[IpAddressReader].id } === Set(IpAddress.ipToLong(ipAddress1))

          // ip address to users
          v.moveTo(VertexDataId[IpAddressReader](ipAddress1))
          nbs = getNeighbors(v, (IpAddressReader, UserReader, TimestampEdgeReader), true)
          nbs.map { x: VertexId => x.asId[UserReader].id } === Set(1, 2)

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

        val libUpdate = LibraryGraphUpdate(libId = Id[Library](1), state = LibraryStates.INACTIVE, libSeq = SequenceNumber[Library](10))
        manager.update(libUpdate)
        manager.readOnly { reader =>
          val v = reader.getNewVertexReader()
          v.hasVertex(VertexId(Id[Library](1))) === false

          v.moveTo(VertexDataId[UserReader](1))
          val nbs = getNeighbors(v, (UserReader, LibraryReader, EmptyEdgeReader), true)
          nbs.map { x: VertexId => x.asId[LibraryReader].id } === Set(2)
        }

      }
    }
  }

}
