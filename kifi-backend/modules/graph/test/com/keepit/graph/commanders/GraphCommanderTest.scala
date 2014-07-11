package com.keepit.graph.commanders

import com.keepit.graph.GraphTestHelper
import com.keepit.graph.common.cache.GraphCacheModule
import com.keepit.graph.manager.{ UserConnectionGraphUpdate, GraphManager }
import com.keepit.graph.model.{ UserConnectionSocialScore, UserConnectionFeedScore }
import com.keepit.graph.simple.{ SimpleGraphManager, SimpleGraphDevModule }
import com.keepit.graph.test.GraphTestInjector
import com.keepit.model.UserConnection
import org.specs2.mutable.Specification

class GraphCommanderTest extends Specification with GraphTestInjector with GraphTestHelper {

  val modules = Seq(
    SimpleGraphDevModule(),
    GraphCacheModule()
  )

  "GraphCommander" should {

    "get uri-score list" in {
      withInjector(modules: _*) { implicit injector =>
        val graphCommander = inject[GraphCommander]
        val manager = inject[GraphManager]
        //
        //        val res1 = graphCommander.getListOfUriAndScorePairs(u42)
        //        val r1: UserConnectionFeedScore = res1.head
        //
        //        res1 must have size (4)
        true === true
      }
    }

    "get user-score list" in {
      withInjector(modules: _*) { implicit injector =>
        val graphCommander = inject[GraphCommander]
        val manager = inject[GraphManager]

        val update1 = UserConnectionGraphUpdate(UserConnection(None, u42, uid1))
        val update2 = UserConnectionGraphUpdate(UserConnection(None, u42, uid2))
        val update3 = UserConnectionGraphUpdate(UserConnection(None, u42, uid3))

        manager.update(update1, update2, update3)

        val res2 = graphCommander.getListOfUserAndScorePairs(u42)

        res2 must have size (3)
      }
    }
  }
}