package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.KifiSession._
import com.keepit.common.controller._
import com.keepit.common.core._
import com.keepit.common.db.slick._
import com.keepit.common.http._
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ RichRequestHeader, UserAgent }
import com.keepit.common.time._
import com.keepit.controllers.routing.{ PreloadSet, KifiSiteRouter }
import com.keepit.heimdal._
import com.keepit.model._
import play.api.Play
import play.api.Play.current
import play.api.libs.iteratee.Enumerator
import play.api.libs.json.Json
import play.api.mvc._
import play.twirl.api.Html
import securesocial.core.{ Authenticator, SecureSocial }

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class HomeController @Inject() (
  db: Database,
  userRepo: UserRepo,
  userValueRepo: UserValueRepo,
  val userActionsHelper: UserActionsHelper,
  userCommander: UserCommander,
  userConnectionsCommander: UserConnectionsCommander,
  heimdalServiceClient: HeimdalServiceClient,
  smsCommander: SmsCommander,
  kifiSiteRouter: KifiSiteRouter,
  implicit val executionContext: ExecutionContext,
  clock: Clock)
    extends UserActions with ShoeboxServiceController with Logging {

  def home = MaybeUserAction.async { implicit request =>
    val special = promoCodeHandler(request)
    request match {
      case _: NonUserRequest[_] => Future.successful(MarketingSiteRouter.marketingSite())
      case _: UserRequest[_] => //kifiSiteRouter.serveWebAppToUser(PreloadSet.userHome)(request)
        Future.successful(Redirect("/keepyourkeeps"))
    }
  }

  def slackIntegration = MaybeUserAction { implicit request =>
    val marketingPage = "integrations/slackv5"
    val special = promoCodeHandler(request)
    if (request.refererOpt.exists(r => r.contains("producthunt.com")) || request.rawQueryString.contains("ref=producthunt")) {
      request match {
        case _: NonUserRequest[_] => MarketingSiteRouter.marketingSite(marketingPage) |> special
        case _: UserRequest[_] => Redirect("/slack-connect") |> special
      }
    } else {
      MarketingSiteRouter.marketingSite(marketingPage)
    }
  }

  private def promoCodeHandler(request: MaybeUserRequest[_]): Result => Result = {
    if (request.refererOpt.exists(r => r.contains("producthunt.com")) || request.rawQueryString.contains("ref=producthunt")) {
      request match {
        case ur: UserRequest[_] =>
          db.readWrite(attempts = 3) { implicit session =>
            userValueRepo.setValue(ur.userId, UserValueName.STORED_CREDIT_CODE, "KIFILOVESSLACK")
          }
          res => res
          case nur: NonUserRequest[_] =>
          res => res
      }
    } else {
      res => res
    }
  }

  def get() = Action { request =>
    val redir = request.userAgentOpt match {
      case Some(ua) if ua.isIphone => "https://itunes.apple.com/us/app/kifi/id740232575"
      case Some(ua) if ua.isAndroid => "https://play.google.com/store/apps/details?id=com.kifi"
      case _ => "/"
    }
    Redirect(redir)
  }

  def robots = Action {
    Ok(
      """
        |User-agent: *
        |Disallow: /admin/
        |
        |SITEMAP: https://www.kifi.com/assets/sitemap-libraries-0.xml
        |SITEMAP: https://www.kifi.com/assets/sitemap-users-0.xml
      """.stripMargin)
  }

  def googleWebmasterToolsSiteVerification = Action {
    Ok(Html("google-site-verification: google25ae05cb8bf5b064.html\n")) // verification for eishay@kifi.com
  }

  def route(path: String) = Action { implicit request =>
    MarketingSiteRouter.marketingSite(path)
  }

  def moved(uri: String) = Action {
    MovedPermanently(uri)
  }

  def iPhoneAppStoreRedirect = MaybeUserAction { implicit request =>
    iPhoneAppStoreRedirectWithTracking
  }
  def iPhoneAppStoreRedirectWithTracking(implicit request: RequestHeader): Result = {
    SafeFuture {
      val context = new HeimdalContextBuilder()
      context.addRequestInfo(request)
      context += ("type", "landing")
      heimdalServiceClient.trackEvent(AnonymousEvent(context.build, EventType("visitor_viewed_page")))
    }
    Ok(views.html.mobile.iPhoneRedirect(request.uri))
  }

  def unsupported = Action {
    Status(200).chunked(Enumerator.fromStream(Play.resourceAsStream("public/unsupported.html").get)) as HTML
  }

  def agent = Action { request =>
    val res = request.headers.get("User-Agent").map { ua =>
      val parsed = UserAgent(ua)
      (parsed.name, parsed.operatingSystemFamily, parsed.operatingSystemName, parsed.typeName, parsed.userAgent, parsed.version)
    }
    Ok(res.toString)
  }

  def install = UserPage { implicit request =>
    SafeFuture {
      if (!hasSeenInstall) userCommander.tellUsersWithContactOfNewUserImmediate(request.user)

      // Temporary event for debugging purpose
      val context = new HeimdalContextBuilder()
      context.addRequestInfo(request)
      heimdalServiceClient.trackEvent(UserEvent(request.user.id.get, context.build, EventType("loaded_install_page")))
    }
    setHasSeenInstall()
    request.userAgentOpt.flatMap { agent =>
      if (agent.isAndroid) {
        Some(Redirect("https://play.google.com/store/apps/details?id=com.kifi&hl=en"))
      } else if (agent.isIphone) {
        Some(Redirect("https://itunes.apple.com/us/app/kifi/id740232575"))
      } else if (!agent.canRunExtensionIfUpToDate) {
        Some(Redirect("/"))
      } else None
    }.getOrElse(Ok(views.html.authMinimal.install()))
  }

  private def hasSeenInstall(implicit request: UserRequest[_]): Boolean = {
    db.readOnlyMaster { implicit s => userValueRepo.getValue(request.userId, UserValues.hasSeenInstall) }
  }

  private def setHasSeenInstall()(implicit request: UserRequest[_]): Unit = {
    db.readWrite(attempts = 3) { implicit s => userValueRepo.setValue(request.userId, UserValues.hasSeenInstall.name, true) }
  }

  // todo: move this to UserController
  def disconnect(networkString: String) = UserAction { implicit request =>
    val (suiOpt, code) = userConnectionsCommander.disconnect(request.userId, networkString)
    suiOpt match {
      case None => code match {
        case "no_other_connected_network" => BadRequest("You must have at least one other network connected.")
        case "not_connected_to_network" => BadRequest(s"You are not connected to ${networkString}.")
        case _ => Status(INTERNAL_SERVER_ERROR)("0")
      }
      case Some(newLoginUser) =>
        val identity = newLoginUser.credentials.get
        Authenticator.create(identity).fold(
          error => Status(INTERNAL_SERVER_ERROR)("0"),
          authenticator => {
            Redirect("/profile") // hard coded because reverse router doesn't let us go there. todo: fix
              .withSession((request.session - SecureSocial.OriginalUrlKey).setUserId(newLoginUser.userId.get))
              .withCookies(authenticator.toCookie)
          }
        )
    }
  }

  // Do not remove until at least 1 Mar 2014. The extension sends users to this URL after installation.
  // It's okay, comment from 21 Jan 2014. This method is safe here.
  // Thanks for assuring me, comment from 3 Sept 2014. Definitely safe.
  def gettingStarted = Action { request =>
    MovedPermanently("/")
  }

  def getKifiExtensionIPhone(s: String) = Action { implicit request =>
    Ok(Html("""<img src="http://djty7jcqog9qu.cloudfront.net/assets/site/keep-from-other-apps.png" style="width: 100%;">"""))
  }

  def sendSmsToGetKifi() = UserAction.async(parse.tolerantJson) { implicit request =>
    val toOpt = (request.body \ "phoneNumber").asOpt[String].map(PhoneNumber.apply)
    val lastSmsSent = db.readOnlyReplica { implicit session =>
      userValueRepo.getValue(request.userId, UserValues.lastSmsSent)
    }
    toOpt match {
      case None =>
        Future.successful(BadRequest(Json.obj("error" -> "invalid_number")))
      case _ if lastSmsSent > clock.now().minusMinutes(1) =>
        Future.successful(BadRequest(Json.obj("error" -> "rate_limit")))
      case Some(number) =>
        db.readWrite { implicit session =>
          userValueRepo.setValue(request.userId, UserValues.lastSmsSent.name, clock.now())
        }
        smsCommander.sendSms(number, "Get Kifi for iOS and Android: https://kifi.com/get").map {
          case SmsSuccess =>
            Ok
          case SmsRemoteFailure =>
            db.readWriteAsync { implicit session =>
              userValueRepo.setValue(request.userId, UserValues.lastSmsSent.name, clock.now().minusMinutes(2))
            }
            InternalServerError
        }
    }
  }

}

object HomeControllerRoutes {
  def home() = "/"
  def install() = "/install"
  def unsupported() = "/unsupported"
}
