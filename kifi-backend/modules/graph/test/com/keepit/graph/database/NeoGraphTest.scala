package com.keepit.graph.database

import org.specs2.mutable.Specification
import com.keepit.graph.model._
import com.keepit.graph.GraphTestInjector
import com.keepit.model._
import com.keepit.common.db.Id

class NeoGraphTest extends Specification with GraphTestInjector {

  val someUserData = Seq(
    UserData(Id[User](1), UserStates.ACTIVE),
    UserData(Id[User](2), UserStates.ACTIVE),
    UserData(Id[User](3), UserStates.INACTIVE),
    UserData(Id[User](4), UserStates.ACTIVE)
  )
  
  val someFollowsData = Seq(
    FollowsData(Id[UserConnection](1), UserConnectionStates.ACTIVE),
    FollowsData(Id[UserConnection](2), UserConnectionStates.ACTIVE),
    FollowsData(Id[UserConnection](3), UserConnectionStates.INACTIVE),
    FollowsData(Id[UserConnection](4), UserConnectionStates.ACTIVE),
    FollowsData(Id[UserConnection](5), UserConnectionStates.INACTIVE),
    FollowsData(Id[UserConnection](6), UserConnectionStates.ACTIVE)
  )

  "NeoGraph" should {
    
    "create and retrieve User vertices" in {
      withGraphDb() { implicit injector =>
      val graph = inject[NeoGraph[VertexData, EdgeData]]
        val vertices = graph.createVertices(someUserData: _*)
        val indices = vertices.map(_.id)

        graph.getVertices(indices: _*) === vertices
        vertices.map(_.data) === someUserData
      }
    }

    "create and retrieve Follows edges " in {
      withGraphDb() { implicit injector =>
        val graph = inject[NeoGraph[VertexData, EdgeData]]
        val vertices = graph.createVertices(someUserData: _*)
        val indices = vertices.map(_.id)

        graph.getVertices(indices: _*) === vertices
        vertices.map(_.data) === someUserData
      }
    }
  }
}

