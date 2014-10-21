package com.keepit.controllers.website

import com.google.inject.{ Provider, Inject, Singleton }
import com.keepit.common.http._
import com.keepit.common.controller._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.net.UserAgent
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import play.api.mvc.{ Result, Request }
import play.api.libs.concurrent.Execution.Implicits._
import ImplicitHelper._
import java.net.{ URLEncoder, URLDecoder }

import scala.concurrent.Future

sealed trait Routeable
trait AngularRoute extends Routeable
private case class AngularLoggedIn(preload: Seq[MaybeUserRequest[_] => Future[String]] = Seq.empty) extends AngularRoute
private case class Angular(preload: Seq[Request[_] => Future[String]] = Seq.empty) extends AngularRoute
private case class RedirectRoute(url: String) extends Routeable
private case object Error404 extends Routeable

case class Path(requestPath: String) {
  val path = if (requestPath.indexOf('/') == 0) {
    requestPath.drop(1)
  } else {
    requestPath
  }
  val split = path.split("/").map(URLDecoder.decode(_, "UTF-8"))
  val primary = split.head
  val secondary = split.tail.headOption
}

@Singleton // holds state for performance reasons
class KifiSiteRouter @Inject() (
  homeController: Provider[HomeController],
  angularRouter: AngularRouter,
  applicationConfig: FortyTwoConfig,
  db: Database,
  val userActionsHelper: UserActionsHelper)
    extends UserActions with ShoeboxServiceController {

  val redirects = Map[String, String](
    "recommendation" -> "/recommendations" //can be removed after Sept. 10th 2014 -Stephen
  )

  // Useful to route anything that a) serves the Angular app, b) requires context about if a user is logged in or not
  def app(path: String) = MaybeUserAction(r => routeRequest(r))

  def home = app("home")

  // When we refactor the authenticator to stop requiring two functions, this can be simplified.
  def routeRequest[T](request: MaybeUserRequest[T]): Result = {
    // Short-circuit for landing pages
    val userAgentOpt = request.userAgentOpt
    if (request.host.contains("42go")) {
      MovedPermanently(applicationConfig.applicationBaseUrl + "/about/mission")
    } else if (request.path == "/" && request.userIdOpt.isEmpty) {
      landingPage(request)
    } else if (userAgentOpt.exists(_.isMobile) && request.queryString.get("kma").exists(_.contains("1"))) {
      val uriNoProto = applicationConfig.applicationBaseUrl.replaceFirst("https?:", "") + request.uri
      Ok(views.html.mobile.MobileRedirect(uriNoProto))
    } else {
      (request, route(request)) match {
        case (_, Error404) =>
          NotFound(views.html.error.notFound(request.path))
        case (r: UserRequest[T], ng: AngularLoggedIn) =>
          // logged in user, logged in only ng. deliver.
          AngularDistAssets.angularApp(ng.preload.map(s => s(r)))
        case (r: MaybeUserRequest[T], route: RedirectRoute) =>
          Redirect(route.url)
        case (r: NonUserRequest[T], _) if r.identityOpt.isDefined =>
          // non-authed client, but identity is set. Mid-signup, send them there.
          Redirect(com.keepit.controllers.core.routes.AuthController.signupPage())
        case (r: MaybeUserRequest[T], ng: AngularRoute) =>
          // routing to ng page
          AngularDistAssets.angularApp()
      }
    }
  }

  private def landingPage(request: MaybeUserRequest[_]): Result = {
    request match {
      case r: NonUserRequest[_] if r.identityOpt.isDefined =>
        Redirect(com.keepit.controllers.core.routes.AuthController.signupPage())
      case _ =>
        val agent = UserAgent(request)
        if (!agent.screenCanFitWebApp) {
          val ua = agent.userAgent
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

  def route(request: MaybeUserRequest[_]): Routeable = {
    val path = Path(request.path)

    redirects.get(path.path).map { targetPath =>
      RedirectRoute(targetPath)
    } getOrElse {
      db.readOnlyReplica { implicit session =>
        angularRouter.route(request, path) getOrElse Error404
      }
    }
  }

}

@Singleton
class AngularRouter @Inject() (userRepo: UserRepo, libraryRepo: LibraryRepo) {

  def route(request: MaybeUserRequest[_], path: Path)(implicit session: RSession): Option[Routeable] = {
    ngStaticPage(request, path) orElse userOrLibrary(request, path)
  }

  def injectUser(request: MaybeUserRequest[_]) = Future {
    "" // inject user data!
  }
  private val ngFixedRoutes: Map[String, Seq[MaybeUserRequest[_] => Future[String]]] = Map(
    "" -> Seq(),
    "invite" -> Seq(),
    "profile" -> Seq(),
    "kifeeeed" -> Seq(),
    "find" -> Seq(),
    "recommendations" -> Seq(),
    "tags/manage" -> Seq()
  )
  private val ngPrefixRoutes: Map[String, Seq[MaybeUserRequest[_] => Future[String]]] = Map(
    "friends" -> Seq(),
    "keep" -> Seq(),
    "tag" -> Seq(),
    "helprank" -> Seq()
  )

  //private val dataOnEveryAngularPage = Seq(injectUser _) // todo: Have fun with this!

  // combined to re-use User lookup
  private def userOrLibrary(request: MaybeUserRequest[_], path: Path)(implicit session: RSession): Option[Routeable] = {
    if (path.split.length == 1 || path.split.length == 2) {
      val userOpt = userRepo.getByUsername(Username(path.primary))

      userOpt.flatMap { user =>
        if (user.username.value != path.primary) {
          val redir = "/" + (user.username.value +: path.split.drop(1)).map(r => URLEncoder.encode(r, "UTF-8")).mkString("/")
          Some(RedirectRoute(redir))
        } else if (path.split.length == 1) { // user profile page
          Some(Angular()) // great place to preload request data since we have `user` available
        } else {
          val libOpt = libraryRepo.getBySlugAndUserId(user.id.get, LibrarySlug(path.secondary.get))
          if (libOpt.isDefined) {
            Some(Angular()) // great place to preload request data since we have `lib` available
          } else {
            None
          }
        }
      }
    } else {
      None
    }
  }

  // Some means to serve Angular. The Seq is possible injected data to include
  // Right now, all static routes are for Users only. If this changes, update this!
  private def ngStaticPage(request: MaybeUserRequest[_], path: Path) = {
    request match {
      case u: UserRequest[_] =>
        (ngFixedRoutes.get(path.path) orElse ngPrefixRoutes.get(path.primary)).map { dataLoader =>
          AngularLoggedIn(dataLoader)
        }
      case n: NonUserRequest[_] => None
    }
  }
}
