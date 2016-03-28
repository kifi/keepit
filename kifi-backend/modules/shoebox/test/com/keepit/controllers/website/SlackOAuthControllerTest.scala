package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.net.URI
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model._
import com.keepit.slack._
import com.keepit.slack.models._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.mvc.Call
import play.api.test.FakeRequest
import play.api.test.Helpers._
import com.keepit.common.core._
import UserFactoryHelper._

class SlackOAuthControllerTest extends Specification with ShoeboxTestInjector {
  implicit def createFakeRequest(route: Call) = FakeRequest(route.method, route.url)
  implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]
  private def controller(implicit injector: Injector) = inject[SlackOAuthController]
  private def route = com.keepit.controllers.website.routes.SlackOAuthController

  val slackTeamId = SlackTeamId("TFAKE")

  def setup()(implicit injector: Injector): Unit = {
    val user = db.readWrite { implicit s => UserFactory.user().saved }
    inject[FakeUserActionsHelper].setUser(user)
  }

  def getPendingAddSlackTeamAction(slackTeamId: Option[SlackTeamId], extraScopes: Option[Set[SlackAuthScope]])(implicit injector: Injector): SlackAuthenticatedAction = {
    val extraScopesStr = extraScopes.map(SlackAuthScope.stringifySet)
    val request = route.addSlackTeam(slackTeamId, extraScopes = extraScopesStr)
    val response = controller.addSlackTeam(slackTeamId, extraScopesStr)(request)
    status(response) === SEE_OTHER

    val redirectUrlOpt = redirectLocation(response)
    val actionOpt = for {
      redirectUrl <- redirectUrlOpt tap (_ should beSome)
      redirectUri <- URI.safelyParse(redirectUrl) tap (_ should beSome)
      query <- redirectUri.query tap (_ should beSome)
      stateParam <- query.getParam("state") tap (_ should beSome)
      state <- stateParam.value.map(SlackAuthState(_)) tap (_ should beSome)
      action <- inject[SlackAuthStateCache].direct.get(SlackAuthStateKey(state)) tap (_ should beSome)
    } yield action

    actionOpt.get
  }

  val controllerTestModules = Seq(
    FakeHeimdalServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )

  "SlackOAuthController" should {
    "Add to Slack" in {
      "Do BackfillScopes when extra Slack scopes are provided" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          setup()
          val extraScopes = Set(SlackAuthScope.ChannelsRead, SlackAuthScope.ChannelsWrite)
          val action = getPendingAddSlackTeamAction(Some(slackTeamId), Some(extraScopes))
          action === AddSlackTeam(andThen = Some(BackfillScopes(extraScopes)))
        }
      }

      "Do not BackfillScopes when extra Slack scopes are not provided" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          setup()
          val action = getPendingAddSlackTeamAction(Some(slackTeamId), None)
          action === AddSlackTeam(andThen = None)
        }
      }

      "Do not BackfillScopes when empty Slack scopes are provided" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          setup()
          val extraScopes = Set.empty[SlackAuthScope]
          val action = getPendingAddSlackTeamAction(Some(slackTeamId), Some(extraScopes))
          action === AddSlackTeam(andThen = None)
        }
      }
    }
  }
}

