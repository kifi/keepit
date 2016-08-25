package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders.{PathCommander, LibraryCommander, UserCommander}
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.{KifiUrlRedirectHelper, PublicIdConfiguration, FakeCryptoModule}
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.{Param, Query}
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.heimdal.{UserEventTypes, HeimdalContext}
import com.keepit.inject.FakeFortyTwoModule
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeKeepImportsModule
import com.keepit.slack.models.{SlackTeamId, SlackUserId}
import com.keepit.test.{ ShoeboxApplication, ShoeboxApplicationInjector }
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._


import org.specs2.mutable.Specification
import org.specs2.matcher.{ Matcher, Expectable }

import play.api.mvc.Result
import play.api.test._
import play.api.test.Helpers._

import scala.concurrent.Future

import securesocial.core.SecureSocial

class KifiSiteRouterTest extends Specification with ShoeboxApplicationInjector {

  args(skipAll = true)

  val modules = Seq(
    FakeExecutionContextModule(),
    FakeActorSystemModule(),
    FakeMailModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeSearchServiceClientModule(),
    FakeShoeboxStoreModule(),
    FakeCortexServiceClientModule(),
    FakeKeepImportsModule(),
    FakeCryptoModule(),
    FakeFortyTwoModule()
  )



  "KifiSiteRouter" should {
    implicit val context = HeimdalContext.empty

    implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]

    // Custom matchers, TODO: find better home
    case class beRedirect(expectedStatus: Int, expectedUrl: String) extends Matcher[Option[Future[Result]]] {
      def apply[T <: Option[Future[Result]]](x: Expectable[T]) = {
        x.value map { resultF =>
          val resStatus = status(resultF)
          if (resStatus != expectedStatus) {
            result(false, "", s"expected status $expectedStatus but was $resStatus", x)
          }
          redirectLocation(resultF) map { resUrl =>
            result(resUrl == expectedUrl, "", s"expected redirect to $expectedUrl but was $resUrl", x)
          } getOrElse result(false, "", "expected redirect to $expectedUrl but was None", x)
        } getOrElse result(false, "", "request did not match any routes", x)
      }
    }
    case class beLoginRedirect(expectedUrl: String) extends Matcher[Option[Future[Result]]] {
      def apply[T <: Option[Future[Result]]](x: Expectable[T]) = {
        x.value map { resultF =>
          val expectedStatus = SEE_OTHER
          val resStatus = status(resultF)
          if (resStatus != expectedStatus) {
            result(false, "", s"expected status $expectedStatus but was $resStatus", x)
          }
          val loginUrl = "/login"
          redirectLocation(resultF) map { resUrl =>
            if (resUrl != loginUrl) {
              result(false, "", s"expected redirect to $loginUrl but was $resUrl", x)
            } else {
              val destUrl = session(resultF).get(SecureSocial.OriginalUrlKey)
              result(destUrl == Some(expectedUrl), "", s"expected destination $expectedUrl in session cookie but was $destUrl", x)
            }
          } getOrElse result(false, "", "expected redirect to $expectedUrl but was None", x)
        } getOrElse result(false, "", "request did not match any routes", x)
      }
    }
    object beWebApp extends Matcher[Option[Future[Result]]] {
      def apply[T <: Option[Future[Result]]](x: Expectable[T]) = {
        x.value map { resultF =>
          val expectedStatus = 200
          val resStatus = status(resultF)
          if (resStatus != expectedStatus) {
            result(false, "", s"expected status $expectedStatus but was $resStatus", x)
          }
          val expectedContentType = Some("text/html")
          var resContentType = contentType(resultF)
          if (resContentType != expectedContentType) {
            result(false, "", s"expected content type $expectedContentType but was $resContentType", x)
          } else {
            val expectedContent = "angular.bootstrap(document, ['kifi'])"
            val resContent = contentAsString(resultF)
            result(resContent.contains(expectedContent), "", s"""expected content to contain "$expectedContent" but was:\n$resContent""", x)
          }
        } getOrElse result(false, "", "request did not match any routes", x)
      }
    }
    object be404 extends Matcher[Option[Future[Result]]] {
      def apply[T <: Option[Future[Result]]](x: Expectable[T]) = {
        x.value map { resultF =>
          val expectedStatus = 404
          val resStatus = status(resultF)
          result(resStatus == expectedStatus, "", s"expected status $expectedStatus but was $resStatus", x)
        } getOrElse result(true, "", "", x)
      }
    }


    "route correctly" in {
      running(new ShoeboxApplication(modules: _*)) {
        // Database population
        val (user1, user2, org) = db.readWrite { implicit session =>
          val u1 = UserFactory.user().withName("Abe", "Lincoln").withUsername("abez").saved
          val u2 = UserFactory.user().withName("Léo", "HasAnAccentInHisName").withUsername("léo1221").saved
          val org = OrganizationFactory.organization().withName("Kifi").withHandle(OrganizationHandle("kifiorghandle")).withOwner(u1).saved
          (u1, u2, org)
        }

        val userCommander = inject[UserCommander]
        userCommander.setUsername(user1.id.get, Username("abez"))
        userCommander.setUsername(user2.id.get, Username("léo1221"))

        val actionsHelper = inject[FakeUserActionsHelper]

        // Site root
        actionsHelper.unsetUser

        {
          val Some(resF) = route(FakeRequest("GET", "/"))
          status(resF) === 200
          Some(resF) must not(beWebApp)
        }

        actionsHelper.setUser(user1)

        {
          val Some(resF) = route(FakeRequest("GET", "/"))
          Some(resF) must beWebApp
          contentAsString(resF) must contain("""<title id="kf-authenticated">Kifi</title>""")
        }

        // User profiles
        actionsHelper.unsetUser
        route(FakeRequest("GET", "/asdf")) must be404
        route(FakeRequest("GET", "/abez")) must beWebApp
        route(FakeRequest("GET", "/abez/libraries")) must beWebApp
        route(FakeRequest("GET", "/abez/libraries/following")) must beWebApp
        route(FakeRequest("GET", "/abez/libraries/invited")) must beLoginRedirect("/me/libraries/invited")
        route(FakeRequest("GET", "/abez/libraries1234/invited")) must be404
        route(FakeRequest("GET", "/leo1221")) must beRedirect(SEE_OTHER, "/l%C3%A9o1221")
        route(FakeRequest("GET", "/léo1221")) must beWebApp
        route(FakeRequest("GET", "/léo1222")) must be404

        actionsHelper.setUser(user1)
        route(FakeRequest("GET", "/abez")) must beWebApp
        route(FakeRequest("GET", "/abez/libraries")) must beWebApp
        route(FakeRequest("GET", "/abez/libraries/following")) must beWebApp
        route(FakeRequest("GET", "/abez/libraries/invited")) must beWebApp
        route(FakeRequest("GET", "/léo1221/libraries/following")) must beWebApp
        route(FakeRequest("GET", "/léo1221/libraries/invited")) must be404

        userCommander.setUsername(user1.id.get, Username("abe.z1234"))
        route(FakeRequest("GET", "/abez")) must beRedirect(MOVED_PERMANENTLY, "/abe.z1234")
        route(FakeRequest("GET", "/abe.z1234")) must beWebApp
        route(FakeRequest("GET", "/abe.z1234/libraries")) must beWebApp
        route(FakeRequest("GET", "/abe.z1234/libraries/following")) must beWebApp
        route(FakeRequest("GET", "/abe.z1234/libraries/invited")) must beWebApp

        // Libraries
        val libraryCommander = inject[LibraryCommander]
        val Right(library) = {
          val libraryRequest = LibraryInitialValues(name = "Awesome Lib", visibility = LibraryVisibility.PUBLISHED)
          libraryCommander.createLibrary(libraryRequest, user1.id.get)
        }
        actionsHelper.unsetUser
        route(FakeRequest("GET", "/abe.z1234/awesome-lib")) must beWebApp
        route(FakeRequest("GET", "/abe.z1234/awesome-lib?auth=abcdefghiklmnop")) must beWebApp
        route(FakeRequest("GET", "/abe.z1234/awesome-lib/find?q=weee")) must beWebApp
        route(FakeRequest("GET", "/abeZ1234/awesome-lib")) must beRedirect(SEE_OTHER, "/abe.z1234/awesome-lib")
        route(FakeRequest("GET", "/abeZ1234/awesome-lib?auth=abcdefghiklmnop")) must beRedirect(SEE_OTHER, "/abe.z1234/awesome-lib?auth=abcdefghiklmnop")
        route(FakeRequest("GET", "/abeZ1234/awesome-lib/find?q=weee")) must beRedirect(SEE_OTHER, "/abe.z1234/awesome-lib/find?q=weee")

        libraryCommander.modifyLibrary(library.id.get, library.ownerId, LibraryModifications(slug = Some("most-awesome-lib")))
        route(FakeRequest("GET", "/abe.z1234/awesome-lib")) must beRedirect(MOVED_PERMANENTLY, "/abe.z1234/most-awesome-lib")
        route(FakeRequest("GET", "/abe.z1234/most-awesome-lib")) must beWebApp

        // Logged-in page routes
        route(FakeRequest("GET", "/invite")) must beLoginRedirect("/invite")
        route(FakeRequest("GET", "/settings")) must beLoginRedirect("/settings")
        route(FakeRequest("GET", "/tags/manage")) must beLoginRedirect("/tags/manage")
        actionsHelper.setUser(user1)
        route(FakeRequest("GET", "/invite")) must beWebApp
        route(FakeRequest("GET", "/settings")) must beWebApp
        route(FakeRequest("GET", "/tags/manage")) must beWebApp

        // /me
        actionsHelper.unsetUser
        route(FakeRequest("GET", "/me")) must beLoginRedirect("/me")
        route(FakeRequest("GET", "/me/libraries/following")) must beLoginRedirect("/me/libraries/following")

        actionsHelper.setUser(user1)
        route(FakeRequest("GET", "/me")) must beRedirect(SEE_OTHER, "/abez")
        route(FakeRequest("GET", "/me/libraries/invited")) must beRedirect(SEE_OTHER, "/abez/libraries/invited")

        // Redirects (logged out)
        actionsHelper.unsetUser
        route(FakeRequest("GET", "/recommendations")) must beLoginRedirect("/")
        route(FakeRequest("GET", "/connections")) must beLoginRedirect("/me/connections")
        route(FakeRequest("GET", "/friends")) must beLoginRedirect("/me/connections")
        route(FakeRequest("GET", "/friends/invite")) must beLoginRedirect("/invite")
        route(FakeRequest("GET", "/friends/requests")) must beLoginRedirect("/me/connections")
        route(FakeRequest("GET", "/friends/requests/email")) must beLoginRedirect("/me/connections")
        route(FakeRequest("GET", "/friends/requests/linkedin")) must beLoginRedirect("/me/connections")
        route(FakeRequest("GET", "/friends/requests/facebook")) must beLoginRedirect("/me/connections")
        route(FakeRequest("GET", "/friends/requests/refresh")) must beLoginRedirect("/me/connections")
        route(FakeRequest("GET", "/friends?friend=" + user2.externalId)) must beLoginRedirect("/l%C3%A9o1221?intent=connect")
        route(FakeRequest("GET", "/invite?friend=" + user2.externalId)) must beLoginRedirect("/l%C3%A9o1221?intent=connect")

        // Redirects (logged in)
        actionsHelper.setUser(user1)
        route(FakeRequest("GET", "/recommendations")) must beRedirect(MOVED_PERMANENTLY, "/")
        route(FakeRequest("GET", "/connections")) must beRedirect(SEE_OTHER, "/abez/connections")
        route(FakeRequest("GET", "/friends")) must beRedirect(SEE_OTHER, "/abez/connections")
        route(FakeRequest("GET", "/friends/invite")) must beRedirect(MOVED_PERMANENTLY, "/invite")
        route(FakeRequest("GET", "/friends/requests")) must beRedirect(SEE_OTHER, "/abez/connections")
        route(FakeRequest("GET", "/friends/requests/email")) must beRedirect(SEE_OTHER, "/abez/connections")
        route(FakeRequest("GET", "/friends/requests/linkedin")) must beRedirect(SEE_OTHER, "/abez/connections")
        route(FakeRequest("GET", "/friends/requests/facebook")) must beRedirect(SEE_OTHER, "/abez/connections")
        route(FakeRequest("GET", "/friends/requests/refresh")) must beRedirect(SEE_OTHER, "/abez/connections")
        route(FakeRequest("GET", "/friends?friend=" + user2.externalId)) must beRedirect(SEE_OTHER, "/l%C3%A9o1221?intent=connect")
        route(FakeRequest("GET", "/invite?friend=" + user2.externalId)) must beRedirect(SEE_OTHER, "/l%C3%A9o1221?intent=connect")

        // user-or-orgs
        val orgLibrary = db.readWrite { implicit session =>
          LibraryFactory.library().withName("Kifi Library").withSlug("kifi-lib").withOwner(user1).withOrganizationIdOpt(org.id).saved
        }

        // non-users can see orgs
        route(FakeRequest("GET", "/kifiorghandle")) must beWebApp
        route(FakeRequest("GET", "/kifiorghandle/members")) must beWebApp
        route(FakeRequest("GET", "/kifiorghandle/libraries")) must beWebApp
        route(FakeRequest("GET", "/kifiorghandle/kifi-lib")) must beWebApp

        // users can see orgs
        actionsHelper.setUser(user2)
        route(FakeRequest("GET", "/kifiorghandle")) must beWebApp
        route(FakeRequest("GET", "/kifiorghandle/members")) must beWebApp
        route(FakeRequest("GET", "/kifiorghandle/libraries")) must beWebApp
        route(FakeRequest("GET", "/kifiorghandle/kifi-lib")) must beWebApp

        // owner can see org
        actionsHelper.setUser(user1)
        route(FakeRequest("GET", "/kifiorghandle")) must beWebApp
        route(FakeRequest("GET", "/kifiorghandle/members")) must beWebApp
        route(FakeRequest("GET", "/kifiorghandle/libraries")) must beWebApp
        route(FakeRequest("GET", "/kifiorghandle/kifi-lib")) must beWebApp

        // for pricing page:
        // non-users get redirected to login
        actionsHelper.unsetUser()
        route(FakeRequest("GET", "/pricing")) must beRedirect(SEE_OTHER, "/about/pricing")
        // for someone in an org, it redirects them to that org's plan page
        actionsHelper.setUser(user1)
        route(FakeRequest("GET", "/pricing")) must beRedirect(SEE_OTHER, "/kifiorghandle/settings/plan")
        // for someone without an org, it redirects to org creation
        actionsHelper.setUser(user2)
        route(FakeRequest("GET", "/pricing")) must beRedirect(SEE_OTHER, "/teams/new")

        val (lib, keep) = db.readWrite { implicit s =>
          val lib = LibraryFactory.library().withName("Lincoln's Speeches").withOwner(user1.id.get).withVisibility(LibraryVisibility.PUBLISHED).saved
          val keep = KeepFactory.keep().withUser(user1).withLibrary(lib).withTitle("The Gettysburg Address").withNote("Four score and seven years ago...")
            .withUrl("www.lincoln.gov/speeches/gettysburg").saved
          (lib, keep)
        }

        // keep pages
        val keepPath = inject[PathCommander].pathForKeep(keep)
        actionsHelper.setUser(user1)
        route(FakeRequest("GET", keepPath.relativeWithLeadingSlash)) must beWebApp

        actionsHelper.setUser(user2)
        route(FakeRequest("GET", keepPath.relativeWithLeadingSlash)) must beWebApp

        libraryCommander.modifyLibrary(lib.id.get, lib.ownerId, LibraryModifications(visibility = Some(LibraryVisibility.SECRET)))
        actionsHelper.setUser(user1)
        route(FakeRequest("GET", keepPath.relativeWithLeadingSlash)) must beWebApp

        actionsHelper.setUser(user2)
        route(FakeRequest("GET", keepPath.relativeWithLeadingSlash)) must be404


        // catching mobile
        {
          val Some(resF) = route(FakeRequest("GET", "/some/path?kma=1").withHeaders("user-agent" -> "iPhone"))
          status(resF) === OK
          contentType(resF) === Some("text/html")
          val content = contentAsString(resF)
          content must contain("window.location = 'kifi://some/path?kma=1';")
          content must contain("window.location = 'intent://some/path?kma=1#Intent;package=com.kifi;scheme=kifi;action=com.kifi.intent.action.APP_EVENT;end;';")
        }

        contentAsString(route(FakeRequest("GET", s"/${user2.username.value}?intent=connect&id=${user2.externalId.id}&invited=1&kma=1").withHeaders("user-agent" -> "iPhone")).get) must contain(
          s"window.location = 'kifi://friends?friend=${user2.externalId.id}';")

        contentAsString(route(FakeRequest("GET", s"/${user2.username.value}?intent=connect&id=${user2.externalId.id}&kma=1").withHeaders("user-agent" -> "iPhone")).get) must contain(
          s"window.location = 'kifi://invite?friend=${user2.externalId.id}';")

        contentAsString(route(FakeRequest("GET", s"/${user2.username.value}?intent=connect&kma=1").withHeaders("user-agent" -> "iPhone")).get) must contain(
          s"window.location = 'kifi://invite?friend=${user2.externalId.id}';")

        // ignoring query param with non-mobile device
        route(FakeRequest("GET", "/some/path?kma=1").withHeaders("user-agent" -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.8; rv:20.0) Gecko/20100101 Firefox/20.0")) must be404

        // ignoring mobile device without query param
        route(FakeRequest("GET", "/some/path").withHeaders("user-agent" -> "Mozilla/5.0 (iPhone)")) must be404


        // Deep link v2 redirects
        val lid = "l8rlPD6Bk7A9" // If public id config changes, this breaks. Need to hard code because the base64 encoding below.

        // Positive examples:
        contentAsString(route(FakeRequest("GET", s"/redir?data=%7B%22t%22%3A%22lv%22%2C%22lid%22%3A%22$lid%22%7D")).get) must contain("/abe.z1234/most-awesome-lib")
        contentAsString(route(FakeRequest("GET", s"/redir?data=eyJ0IjoibHYiLCJsaWQiOiJsOHJsUEQ2Qms3QTkifQo%3D")).get) must contain("/abe.z1234/most-awesome-lib")
        // Permissive examples (we'll allow it, but this format is not preferred):
        status(route(FakeRequest("GET", s"/redir?data=%7B%26quot;t%26quot;:%26quot;lv%26quot;,%26quot;lid%26quot;:%26quot;$lid%26quot;%7D&kma=1")).get) === 200
        status(route(FakeRequest("GET", s"""/redir?data=%7B"t":"lv","lid":"$lid"%7D&kma=1""")).get) === 200

        // Broken examples:
        status(route(FakeRequest("GET", s"/redir?data=%7B%22t%22%3A%22lv%22%2C%22lid%22%3A%22l8rlPD6Bk7A0%22%7D")).get) === 303
        status(route(FakeRequest("GET", s"/redir?data=AAABBjoibHYiLCJsaWQiOiJsOHJsUEQ2Qms3QTkifQo%3D")).get) === 303

        val deepLinkKeep = keep // see keep page tests above
        val kid = Keep.publicId(deepLinkKeep.id.get)
        val uriExtId = db.readOnlyMaster(implicit s => uriRepo.get(keep.uriId).externalId)
        actionsHelper.setUser(user1)
        val request = FakeRequest("GET", s"/redir?data=%7B%22t%22%3A%22m%22%2C%22uri%22%3A%22$uriExtId%22%2C%22id%22%3A%22${kid.id}%22%7D")
        contentAsString(route(request).get) must contain(keep.path.relativeWithLeadingSlash)
      }
    }

    "encrypt and decrypt urls using KifiUrlRedirectHelper" in {
      running(new ShoeboxApplication(modules: _*)) {
        withInjector(modules: _*) { implicit injector =>
          implicit val testConfig = FakeFortyTwoModule().fortytwoConfig

          // encrypt/decrypt extra params correctly
          val trackingParams = Query(
            "eventType" -> UserEventTypes.CLICKED_SEARCH_RESULT.name,
            "action" -> "clickedSomething",
            "slackUserId" -> "slacker",
            "slackTeamId" -> "slackers"
          )

          Seq(
            "http://venturebeat.com/2015/11/04/atlassian-launches-hipchat-connect-api-to-let-developers-build-deeper-integrations/",
            "https://twitter.com/SlackAPI/status/689868008706088960",
            "https://medium.com/slack-developer-blog/the-slack-app-directory-checklist-e3f3ba0ca7c5#.7du2ee65d",
            "https://www.kifi.com/kifi/general",
            "http://usepanda.com/"
          ).foreach { url =>
            val wrappedUrl = KifiUrlRedirectHelper.generateKifiUrlRedirect(url, trackingParams)
              .drop(testConfig.applicationBaseUrl.length) // omit host to route properly from test

            // route correctly
            route(FakeRequest("GET", wrappedUrl)) must beRedirect(SEE_OTHER, url)

            // parse tracking params correctly
            val request = FakeRequest("GET", wrappedUrl)
            val signedUrl = request.queryString("s").head
            val signedParams = request.queryString("t").head
            KifiUrlRedirectHelper.parseKifiUrlRedirect(signedUrl, Some(signedParams)).map {
              case (confirmedUrl, trackingParamsOpt) =>
                confirmedUrl must beEqualTo(url)
                trackingParamsOpt.map(_.params.toSet) === Some(trackingParams.params.toSet)
            }
          }

          // route incorrectly on bad urls
          val maliciousUrl = "/url?s=7ffe96665c78152208bf6c026027d50c5ace165b-1453416519729-www.googol.com"
          route(FakeRequest("GET", maliciousUrl)) must beRedirect(SEE_OTHER, "/")
        }
      }
    }
  }
}
