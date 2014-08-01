package com.keepit.graph.commanders

import com.keepit.common.db.SequenceNumber
import com.keepit.graph.{ manager, GraphTestHelper }
import com.keepit.graph.common.cache.GraphCacheModule
import com.keepit.graph.manager._
import com.keepit.graph.model.{ ConnectedUserScore, ConnectedUriScore }
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

        manager.update(createUserUpdate, createFirstDegreeUser, keepGraphUpdate1, keepGraphUpdate2, keepGraphUpdate3, keepGraphUpdate4, keepGraphUpdate5, userConnectionGraphUpdate1, userConnectionGraphUpdate2, userConnectionGraphUpdate3)

        val res1 = graphCommander.getConnectedUriScores(u42, true)

        res1 must have size (4)

        res1 must not contain (ConnectedUriScore(uriid5, 0.0d))

        res1(1).score should be_>=(res1(2).score)

        res1(1).score should be <= 1.0d
      }
    }

    "get user-score list" in {
      withInjector(modules: _*) { implicit injector =>
        val graphCommander = inject[GraphCommander]
        val manager = inject[GraphManager]

        manager.update(createUserUpdate, createFirstDegreeUser, keepGraphUpdate1, keepGraphUpdate2, keepGraphUpdate3, keepGraphUpdate4, keepGraphUpdate5, userConnectionGraphUpdate1, userConnectionGraphUpdate2, userConnectionGraphUpdate3)

        val res2 = graphCommander.getConnectedUserScores(u42, false)

        res2 must have size (4)

        res2(1).score should be_>=(res2(2).score)

        res2(1).score should be <= 1.0d
      }
    }
  }
}
