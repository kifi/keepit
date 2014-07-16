package com.keepit.graph

import com.keepit.common.db.Id
import com.keepit.graph.controllers.internal.GraphController
import com.keepit.graph.manager.GraphManager
import com.keepit.graph.simple.SimpleGraphTestModule
import com.keepit.graph.test.{ GraphApplication, GraphApplicationInjector }
import com.keepit.model.User
import org.specs2.mutable.Specification
import play.api.test.FakeRequest
import play.api.test.Helpers._

class GraphControllerTest extends Specification with GraphApplicationInjector with GraphTestHelper {
  "graph controller" should {
    val modules = Seq(
      TestGraphServiceClientModule(),
      SimpleGraphTestModule())

    "get list of uri and score pairs" in {
      running(new GraphApplication(modules: _*)) {
        val controller = inject[GraphController] //setup
        val manager = inject[GraphManager]
        manager.update(createUserUpdate, createFirstDegreeUser, keepGraphUpdate1, keepGraphUpdate2, keepGraphUpdate3, keepGraphUpdate4, userConnectionGraphUpdate1, userConnectionGraphUpdate2, userConnectionGraphUpdate3)
        val result = controller.getListOfUriAndScorePairs(u42, true)(FakeRequest())
        status(result) must equalTo(OK)
        val content = contentAsString(result)
        content !== null
      }
    }

    "get list of user and score pairs" in {
      running(new GraphApplication(modules: _*)) {
        val controller = inject[GraphController] //setup
        val manager = inject[GraphManager]
        manager.update(createUserUpdate, createFirstDegreeUser, keepGraphUpdate1, keepGraphUpdate2, keepGraphUpdate3, keepGraphUpdate4, userConnectionGraphUpdate1, userConnectionGraphUpdate2, userConnectionGraphUpdate3)
        val result = controller.getListOfUserAndScorePairs(u42, false)(FakeRequest())
        status(result) must equalTo(OK)
        val content = contentAsString(result)
        content !== null
      }
    }
  }

}
