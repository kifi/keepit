package com.keepit.controllers.website

import com.keepit.common.cache.TransactionalCaching.Implicits._
import com.google.inject.{ Provider, Inject, Singleton }
import com.keepit.commanders.LibraryCommander
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.http._
import com.keepit.common.controller._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.KifiMobileAppLinkFlag
import com.keepit.common.net.UserAgent
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.LibraryVisibility.PUBLISHED
import com.keepit.model._
import play.api.Play
import play.api.mvc.{ Result, Request }
import play.api.libs.concurrent.Execution.Implicits._
import ImplicitHelper._
import java.net.{ URLEncoder, URLDecoder }

import scala.concurrent.Future

sealed trait Routeable
private case class Angular(headerload: Option[String], postload: Seq[MaybeUserRequest[_] => Future[String]] = Seq.empty) extends Routeable
private case class RedirectRoute(url: String) extends Routeable
private case class RedirectToLogin(originalUrl: String) extends Routeable
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
      //should we ever get to this line???
      Redirect(com.keepit.controllers.website.routes.HomeController.home)
    } else if (userAgentOpt.exists(_.isMobile) &&
      request.queryString.get(KifiMobileAppLinkFlag.key).exists(_.contains(KifiMobileAppLinkFlag.value))) {
      Ok(views.html.mobile.MobileRedirect(request.uri))
    } else {
      (request, route(request)) match {
        case (_, Error404) =>
          NotFound(views.html.error.notFound(request.path))
        case (_, route: RedirectRoute) =>
          Redirect(route.url)
        case (_, route: RedirectToLogin) =>
          val nRes = Redirect("/login")
          // Less than ideal, but we can't currently test this:
          Play.maybeApplication match {
            case Some(_) => nRes.withSession(request.session + ("original-url" -> request.uri))
            case None => nRes
          }
        case (r: NonUserRequest[T], _) if r.identityOpt.isDefined =>
          // non-authed client, but identity is set. Mid-signup, send them there.
          Redirect(com.keepit.controllers.core.routes.AuthController.signupPage())
        case (r: MaybeUserRequest[T], ng: Angular) =>
          // routing to ng page - could be public pages like public library, user profile and other shared routes with private views
          AngularDistAssets.angularApp(ng.headerload, ng.postload.map(s => s(r)))
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
class AngularRouter @Inject() (
    userRepo: UserRepo,
    libraryCommander: LibraryCommander,
    airbrake: AirbrakeNotifier,
    libraryRepo: LibraryRepo,
    libraryMetadataCache: LibraryMetadataCache) {

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
    "tags/manage" -> Seq(),
    "keeps" -> Seq()
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
          Some(Angular(None)) // great place to postload request data since we have `user` available
        } else {
          libraryRepo.getBySlugAndUserId(user.id.get, LibrarySlug(path.secondary.get)) map { lib =>
            Some(Angular(Some(libMetadata(lib)))) // great place to postload request data since we have `lib` available
          } getOrElse None
        }
      }
    } else {
      None
    }
  }

  private def libMetadata(library: Library): String = try {
    libraryMetadataCache.getOrElse(LibraryMetadataKey(library.id.get)) {
      libraryCommander.libraryMetaTags(library).formatOpenGraph
    }
  } catch {
    case e: Throwable =>
      airbrake.notify(s"on getting library metadata for $library", e)
      ""
  }

  // Some means to serve Angular. The Seq is possible injected data to include
  // Right now, all static routes are for Users only. If this changes, update this!
  private def ngStaticPage(request: MaybeUserRequest[_], path: Path) = {
    request match {
      case u: UserRequest[_] =>
        (ngFixedRoutes.get(path.path) orElse ngPrefixRoutes.get(path.primary)).map { dataLoader =>
          Angular(None, dataLoader)
        }
      case n: NonUserRequest[_] =>
        (ngFixedRoutes.get(path.path) orElse ngPrefixRoutes.get(path.primary)).map { _ =>
          RedirectToLogin(path.requestPath)
        }
    }
  }
}
