package com.keepit.graph

import com.keepit.graph.controllers.internal.GraphController
import com.keepit.graph.manager.GraphManager
import com.keepit.graph.model.{ SociallyRelatedEntitiesForOrg, SociallyRelatedEntitiesForUser }
import com.keepit.graph.simple.SimpleGraphTestModule
import com.keepit.graph.test.{ GraphTestInjector }
import com.keepit.model.User
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

      "get socially related entities for users" in {
        withInjector(modules: _*) { implicit injector =>
          val route = com.keepit.graph.controllers.internal.routes.GraphController.getSociallyRelatedEntitiesForUser(u42).url
          route === "/internal/graph/getSociallyRelatedEntitiesForUser?userId=42"
          val controller = inject[GraphController] //setup
          val manager = inject[GraphManager]
          manager.update(allUpdates: _*)
          val result = controller.getSociallyRelatedEntitiesForUser(u42)(FakeRequest())
          status(result) must equalTo(OK)
          val content = contentAsString(result)
          content !== null

          val sociallyRelatedEntities = Json.fromJson[SociallyRelatedEntitiesForUser](Json.parse(content)).get
          sociallyRelatedEntities.facebookAccounts.id === u42
          sociallyRelatedEntities.facebookAccounts.totalSteps === 0.0
          sociallyRelatedEntities.linkedInAccounts.id === u42
          sociallyRelatedEntities.linkedInAccounts.totalSteps === 0.0
          sociallyRelatedEntities.emailAccounts.id === u42
          sociallyRelatedEntities.emailAccounts.totalSteps === sociallyRelatedEntities.emailAccounts.related.map(_._2).sum
          sociallyRelatedEntities.users.id === u42
          sociallyRelatedEntities.users.related.size === 5
          sociallyRelatedEntities.users.totalSteps === sociallyRelatedEntities.users.related.map(_._2).sum
          sociallyRelatedEntities.totalSteps === 100000
        }
      }

      "get socially related entities for orgs" in {
        withInjector(modules: _*) { implicit injector =>
          val route = com.keepit.graph.controllers.internal.routes.GraphController.getSociallyRelatedEntitiesForOrg(orgId1).url
          route === "/internal/graph/getSociallyRelatedEntitiesForOrg?orgId=1"
          val controller = inject[GraphController]
          val manager = inject[GraphManager]
          manager.update(allUpdates: _*)
          val result = controller.getSociallyRelatedEntitiesForOrg(orgId1)(FakeRequest())
          status(result) must equalTo(OK)
          val content = contentAsString(result)
          content !== null

          val sociallyRelatedEntities = Json.fromJson[SociallyRelatedEntitiesForOrg](Json.parse(content)).get
          sociallyRelatedEntities.emailAccounts.id === orgId1
          sociallyRelatedEntities.emailAccounts.related.size === 2
          sociallyRelatedEntities.emailAccounts.related.map(_._2).sum === sociallyRelatedEntities.emailAccounts.totalSteps
          sociallyRelatedEntities.users.id === orgId1
          sociallyRelatedEntities.users.related.size === 5
          sociallyRelatedEntities.users.totalSteps === sociallyRelatedEntities.users.related.map(_._2).sum
          sociallyRelatedEntities.totalSteps === 100000
        }
      }
    }
  }

}
