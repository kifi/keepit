package com.keepit.graph

import com.keepit.common.db.Id
import com.keepit.graph.controllers.internal.GraphController
import com.keepit.graph.test.{ GraphApplication, GraphApplicationInjector }
import com.keepit.model.User
import org.specs2.mutable.Specification
import play.api.test.FakeRequest
import play.api.test.Helpers._

class GraphControllerTest extends Specification with GraphApplicationInjector with GraphTestHelper {
  "graph controller" should {
    val modules = Seq(
      TestGraphServiceClientModule()
    )

    "get list of uri and score pairs" in {
      running(new GraphApplication(modules: _*)) {
        //val route = com.keepit.graph.routes.GraphController.getListOf
        val controller = inject[GraphController] //setup
        val result = controller.getListOfUriAndScorePairs(Id[User](42))(FakeRequest())
        status(result) must equalTo(OK)
        val content = contentAsString(result)
        content !== null
      }
    }

    "get list of user and score pairs" in {
      running(new GraphApplication(modules: _*)) {
        //val route = com.keepit.graph.routes.GraphController.getListOf
        val controller = inject[GraphController] //setup
        val result = controller.getListOfUserAndScorePairs(Id[User](42))(FakeRequest())
        status(result) must equalTo(OK)
        val content = contentAsString(result)
        content === null
        false === true
      }
    }
  }

}
