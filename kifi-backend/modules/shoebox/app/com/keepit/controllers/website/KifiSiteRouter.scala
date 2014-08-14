package com.keepit.controllers.website

import com.google.inject.{ Provider, Inject, Singleton }
import com.keepit.common.controller.{ AuthenticatedRequest, ActionAuthenticator, ShoeboxServiceController, WebsiteController }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.net.UserAgent
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.{ LibraryRepo, LibrarySlug, UserRepo, Username }
import play.api.mvc.{ SimpleResult, Request }
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.common.controller.ActionAuthenticator.MaybeAuthenticatedRequest

import scala.concurrent.Future

sealed trait Routeable
trait AngularRoute extends Routeable
private case class AngularLoggedIn(preload: Seq[Request[_] => Future[String]] = Seq.empty) extends AngularRoute
private case class Angular(preload: Seq[Request[_] => Future[String]] = Seq.empty) extends AngularRoute
private case class RedirectRoute(url: String) extends Routeable
private case object Error404 extends Routeable

case class Path(requestPath: String) {
  val path = if (requestPath.indexOf('/') == 0) {
    requestPath.drop(1)
  } else {
    requestPath
  }
  val split = path.split("/")
  val primary = split.head
  val secondary = split.tail.headOption
}

@Singleton // holds state for performance reasons
class KifiSiteRouter @Inject() (
  actionAuthenticator: Provider[ActionAuthenticator],
  homeController: Provider[HomeController],
  angularRouter: AngularRouter,
  applicationConfig: FortyTwoConfig,
  db: Database)
    extends WebsiteController(actionAuthenticator.get) with ShoeboxServiceController {

  // Useful to route anything that a) serves the Angular app, b) requires context about if a user is logged in or not
  def app(path: String) = HtmlAction.apply(authenticatedAction = { request =>
    routeRequest(request)
  }, unauthenticatedAction = { request =>
    routeRequest(request)
  })

  def home = app("home")

  // When we refactor the authenticator to stop requiring two functions, this can be simplified.
  def routeRequest[T](request: Request[T]) = {
    // Short-circuit for landing pages
    if (request.host.contains("42go")) {
      MovedPermanently(applicationConfig.applicationBaseUrl + "/about/mission.html")
    } else if (request.path == "/" && request.userIdOpt.isEmpty) {
      landingPage(request)
    } else {
      (request, route(request)) match {
        case (_, Error404) =>
          NotFound // better 404 please!
        case (r: AuthenticatedRequest[T], ng: AngularRoute) =>
          // logged in user, logged in only ng. deliver.
          ng match {
            case ang: AngularLoggedIn =>
              AngularDistAssets.angularApp(ang.preload.map(s => s(r)))
            case ang: Angular =>
              AngularDistAssets.angularApp()
          }
        case (r: Request[T], _) if request.identityOpt.isDefined =>
          // non-authed client, but identity is set. Mid-signup, send them there.
          Redirect(com.keepit.controllers.core.routes.AuthController.signupPage())
        case (r: Request[T], ng: AngularRoute) =>
          // non-authed client, routing to ng page
          Redirect("/") // todo: serve ng app!
      }
    }
  }

  private def landingPage(request: Request[_]): SimpleResult = {
    if (request.identityOpt.isDefined) {
      Redirect(com.keepit.controllers.core.routes.AuthController.signupPage())
    } else {
      val agentOpt = request.headers.get("User-Agent").map(UserAgent.fromString)
      if (agentOpt.exists(!_.screenCanFitWebApp)) {
        val ua = agentOpt.get.userAgent
        val isIphone = ua.contains("iPhone") && !ua.contains("iPad")
        if (isIphone) {
          homeController.get.iPhoneAppStoreRedirectWithTracking(request)
        } else {
          Ok(views.html.marketing.mobileLanding(""))
        }
      } else {
        Ok(views.html.marketing.landing())
      }
    }
  }

  def route(request: Request[_]): Routeable = {
    val path = Path(request.path)

    db.readOnlyReplica { implicit session =>
      angularRouter.route(request, path) getOrElse Error404
    }
  }

}

@Singleton
class AngularRouter @Inject() (userRepo: UserRepo, libraryRepo: LibraryRepo) {

  def route(request: Request[_], path: Path)(implicit session: RSession): Option[Routeable] = {
    ngStaticPage(path) orElse userOrLibrary(path)
  }

  def injectUser(request: Request[_]) = Future {
    "" // inject user data!
  }
  private val ngFixedRoutes: Map[String, Seq[Request[_] => Future[String]]] = Map(
    "" -> Seq(),
    "invite" -> Seq(),
    "profile" -> Seq(),
    "kifeeeed" -> Seq(),
    "find" -> Seq()
  )
  private val ngPrefixRoutes: Map[String, Seq[Request[_] => Future[String]]] = Map(
    "friends" -> Seq(),
    "keep" -> Seq(),
    "tag" -> Seq(),
    "helprank" -> Seq()
  )

  //private val dataOnEveryAngularPage = Seq(injectUser _) // todo: Have fun with this!

  // combined to re-use User lookup
  private def userOrLibrary(path: Path)(implicit session: RSession): Option[AngularLoggedIn] = {
    if (path.split.length == 1 || path.split.length == 2) {
      val userOpt = userRepo.getUsername(Username(path.primary))
      if (userOpt.isDefined) {
        if (path.split.length == 1) { // user profile page
          Some(AngularLoggedIn()) // great place to preload request data since we have `user` available
        } else {
          val libOpt = libraryRepo.getBySlugAndUserId(userOpt.get.id.get, LibrarySlug(path.secondary.get))
          if (libOpt.isDefined) {
            // todo: Determine if user has access. Else, 404 it (github style)
            Some(AngularLoggedIn()) // great place to preload request data since we have `lib` available
          } else {
            None
          }
        }
      } else {
        None
      }
    } else {
      None
    }
  }

  // Some means to serve Angular. The Seq is possible injected data to include
  private def ngStaticPage(path: Path) = {
    (ngFixedRoutes.get(path.path) orElse ngPrefixRoutes.get(path.primary)).map { dataLoader =>
      AngularLoggedIn(dataLoader)
    }
  }
}
