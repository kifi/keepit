package com.keepit.common.oauth

import java.net.URLDecoder

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.common.controller.KifiSession._
import com.keepit.common.core._
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.{ FakeShoeboxAppSecureSocialModule, FakeSocialGraphModule }
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.controllers.core.AuthController
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceModule, KeepImportsModule }
import com.keepit.test.{ ShoeboxApplication, ShoeboxApplicationInjector }
import org.specs2.mutable.Specification
import play.api.test.Helpers._
import play.api.test._

class OAuth2Test extends Specification with ShoeboxApplicationInjector {

  implicit val context = HeimdalContext.empty

  def modules = Seq(
    FakeExecutionContextModule(),
    FakeShoeboxServiceModule(),
    FakeSearchServiceClientModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeAirbrakeModule(),
    FakeABookServiceClientModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    FakeHeimdalServiceClientModule(),
    FakeShoeboxAppSecureSocialModule(),
    FakeUserActionsModule(),
    FakeCortexServiceClientModule(),
    KeepImportsModule(),
    FakeCuratorServiceClientModule(),
    FakeOAuth1ConfigurationModule(),
    FakeOAuth2ConfigurationModule()
  )

  // only checks the initial redirect flow for now; more work required
  def oauth2FlowCheck(providerConfig: OAuth2ProviderConfiguration)(implicit authController: AuthController) = {
    val path = com.keepit.controllers.core.routes.AuthController.signup(providerConfig.name).toString()
    path === s"/signup/${providerConfig.name}"

    val request = FakeRequest("GET", path)
    val result = authController.signup(providerConfig.name)(request)

    status(result) === SEE_OTHER // 303
    val sess = session(result)
    sess.getUserId.isDefined === false // no user (yet)
    contentAsString(result) === ""

    val hdrs = headers(result)
    val locURL = new java.net.URL(hdrs("location"))
    locURL.getHost === providerConfig.authUrl.getHost
    locURL.getPath === providerConfig.authUrl.getPath
    val queryParams = locURL.getQuery.split('&').map { kv =>
      kv.split('=') |> { arr =>
        arr(0) -> arr(1)
      }
    }.toMap
    queryParams.size === 5
    queryParams.get("state").isDefined === true
    queryParams.get("scope").exists { s =>
      URLDecoder.decode(s, "UTF-8") == providerConfig.scope
    } === true
    queryParams.get("client_id").exists(_ == providerConfig.clientId) === true
    queryParams.get("response_type").exists(_ == "code") === true
    queryParams.get("redirect_uri").isDefined === true
    val redirectUrl = new java.net.URL(URLDecoder.decode(queryParams.get("redirect_uri").get, "UTF-8"))
    redirectUrl.getPath === s"/authenticate/${providerConfig.name}"
  }

  "AuthController" should {
    "(signup) starts oauth2.1 redirect flow" in {
      running(new ShoeboxApplication(modules: _*)) {
        implicit val authController = inject[AuthController]

        val oauth2Config = inject[OAuth2Configuration]
        val fbConfig = oauth2Config.getProviderConfig("facebook").get
        oauth2FlowCheck(fbConfig)
        val lnkdConfig = oauth2Config.getProviderConfig("linkedin").get
        oauth2FlowCheck(lnkdConfig)
      }
    }
  }

}
