package com.keepit.graph

import com.keepit.graph.controllers.internal.GraphController
import com.keepit.graph.manager.GraphManager
import com.keepit.graph.model.SociallyRelatedEntities
import com.keepit.graph.simple.SimpleGraphTestModule
import com.keepit.graph.test.{ GraphTestInjector }
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._
import com.keepit.abook.FakeABookServiceClientModule

class GraphControllerTest extends Specification with GraphTestInjector with GraphTestHelper {
  "graph controller" should {
    val modules = Seq(
      FakeGraphServiceModule(),
      FakeABookServiceClientModule(),
      SimpleGraphTestModule())

    "get list of uri and score pairs" in {
      withInjector(modules: _*) { implicit injector =>
        val route = com.keepit.graph.controllers.internal.routes.GraphController.getListOfUriAndScorePairs(u42, true).url
        route === "/internal/graph/getUriAndScorePairs?userId=42&avoidFirstDegreeConnections=true"
        val controller = inject[GraphController] //setup
        val manager = inject[GraphManager]
        manager.update(allUpdates: _*)
        val result = controller.getListOfUriAndScorePairs(u42, true)(FakeRequest())
        status(result) must equalTo(OK)
        val content = contentAsString(result)
        content !== null
      }
    }

    "get list of user and score pairs" in {
      withInjector(modules: _*) { implicit injector =>
        val route = com.keepit.graph.controllers.internal.routes.GraphController.getListOfUserAndScorePairs(u42, false).url
        route === "/internal/graph/getUserAndScorePairs?userId=42&avoidFirstDegreeConnections=false"
        val controller = inject[GraphController] //setup
        val manager = inject[GraphManager]
        manager.update(allUpdates: _*)
        val result = controller.getListOfUserAndScorePairs(u42, false)(FakeRequest())
        status(result) must equalTo(OK)
        val content = contentAsString(result)
        content !== null
      }

      "get socially related entities" in {
        withInjector(modules: _*) { implicit injector =>
          val route = com.keepit.graph.controllers.internal.routes.GraphController.getSociallyRelatedEntities(u42).url
          route === "/internal/graph/getSociallyRelatedEntities?userId=42"
          val controller = inject[GraphController] //setup
          val manager = inject[GraphManager]
          manager.update(allUpdates: _*)
          val result = controller.getSociallyRelatedEntities(u42)(FakeRequest())
          status(result) must equalTo(OK)
          val content = contentAsString(result)
          content !== null

          val jsResult = Json.fromJson[SociallyRelatedEntities](Json.parse(content))
          jsResult.get.facebookAccounts.id == u42
          jsResult.get.linkedInAccounts.id == u42
          jsResult.get.emailAccounts.id == u42
          jsResult.get.users.id == u42
          jsResult.get.users.related.size === 5
        }
      }
    }
  }

}
