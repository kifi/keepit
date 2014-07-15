package com.keepit.graph.commanders

import com.keepit.common.db.SequenceNumber
import com.keepit.graph.{ manager, GraphTestHelper }
import com.keepit.graph.common.cache.GraphCacheModule
import com.keepit.graph.manager._
import com.keepit.graph.model.{ UserConnectionSocialScore, UserConnectionFeedScore }
import com.keepit.graph.simple.{ SimpleGraphTestModule }
import com.keepit.graph.test.GraphTestInjector
import com.keepit.model.{ KeepSource, User, Keep, UserConnection }
import org.specs2.mutable.Specification

class GraphCommanderTest extends Specification with GraphTestInjector with GraphTestHelper {

  val modules = Seq(
    SimpleGraphTestModule(),
    GraphCacheModule())

  "GraphCommander" should {

    "get uri-score list" in {
      withInjector(modules: _*) { implicit injector =>
        val graphCommander = inject[GraphCommander]
        val manager = inject[GraphManager]

        manager.update(createUserUpdate, createFirstDegreeUser, keepGraphUpdate1, keepGraphUpdate2, keepGraphUpdate3, keepGraphUpdate4, userConnectionGraphUpdate1, userConnectionGraphUpdate2, userConnectionGraphUpdate3)

        val res1 = graphCommander.getListOfUriAndScorePairs(u42, true)

        res1 must have size (4)

        res1(1).score should be_>=(res1(2).score)

      }
    }

    "get user-score list" in {
      withInjector(modules: _*) { implicit injector =>
        val graphCommander = inject[GraphCommander]
        val manager = inject[GraphManager]

        manager.update(createUserUpdate, createFirstDegreeUser, keepGraphUpdate1, keepGraphUpdate2, keepGraphUpdate3, keepGraphUpdate4, userConnectionGraphUpdate1, userConnectionGraphUpdate2, userConnectionGraphUpdate3)

        val res2 = graphCommander.getListOfUserAndScorePairs(u42, false)

        res2 must have size (4)

        res2(1).score should be_>=(res2(2).score)
      }
    }
  }
}
