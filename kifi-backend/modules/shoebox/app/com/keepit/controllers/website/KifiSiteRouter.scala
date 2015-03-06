package com.keepit.controllers.website

import java.net.{ URLDecoder, URLEncoder }
import java.util.regex.Pattern

import com.keepit.common.cache.TransactionalCaching.Implicits._
import com.google.inject.{ Provider, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.core._
import com.keepit.common.controller._
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.http._
import com.keepit.common.mail.KifiMobileAppLinkFlag
import com.keepit.common.net.UserAgent
import com.keepit.common.strings.UTF8
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import play.api.Play
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.Result
import securesocial.core.SecureSocial

import scala.concurrent.Future
import scala.util.matching.Regex

sealed trait Routeable
private case class MovedPermanentlyRoute(url: String) extends Routeable
private case class Angular(headerload: Option[Future[String]], postload: Seq[MaybeUserRequest[_] => Future[String]] = Seq.empty) extends Routeable
private case class SeeOtherRoute(url: String) extends Routeable
private case class RedirectToLogin(originalUrl: String) extends Routeable
private case object Error404 extends Routeable

object KifiSiteRouter {
  def substituteMetaProperty(property: String, newContent: String): (Regex, String) = {
    val pattern = ("""<meta\s+property="""" + Pattern.quote(property) + """"\s+content=".*"\s*/?>""").r
    val newValue = s"""<meta property="$property" content="$newContent"/>"""
    pattern -> newValue
  }

  def substituteLink(rel: String, newRef: String): (Regex, String) = {
    val pattern = ("""<link\s+rel="""" + Pattern.quote(rel) + """"\s+href=".*"\s*/?>""").r
    val newValue = s"""<link rel="$rel" href="$newRef"/>"""
    pattern -> newValue
  }
}

@Singleton // holds state for performance reasons
class KifiSiteRouter @Inject() (
  angularRouter: AngularRouter,
  applicationConfig: FortyTwoConfig,
  db: Database,
  val userActionsHelper: UserActionsHelper)
    extends UserActions with ShoeboxServiceController {

  // Useful to route anything that a) serves the Angular app, b) requires context about if a user is logged in or not
  def app(path: String) = MaybeUserAction(r => routeToResult(r))

  def routeToResult[T](request: MaybeUserRequest[T]): Result = {
    if (request.host.contains("42go")) {
      MovedPermanently(applicationConfig.applicationBaseUrl + "/about/mission")
    } else if (request.userAgentOpt.exists(_.isMobile) &&
      request.queryString.get(KifiMobileAppLinkFlag.key).exists(_.contains(KifiMobileAppLinkFlag.value))) {
      Ok(views.html.mobile.MobileRedirect(request.uri))
    } else if (request.userOpt.isEmpty && request.identityOpt.isDefined) {
      // non-authed client, but identity is set. mid-signup, send them there.
      Redirect(com.keepit.controllers.core.routes.AuthController.signupPage())
    } else {
      route(request) match {
        case Error404 =>
          NotFound(views.html.error.notFound(request.path))
        case route: SeeOtherRoute =>
          Redirect(route.url)
        case route: MovedPermanentlyRoute =>
          MovedPermanently(route.url)
        case _: RedirectToLogin =>
          val nRes = Redirect("/login")
          // Less than ideal, but we can't currently test this:
          Play.maybeApplication match {
            case Some(_) => nRes.withSession(request.session + (SecureSocial.OriginalUrlKey -> request.uri))
            case None => nRes
          }
        case ng: Angular =>
          // routing to ng page - could be public pages like public library, user profile and other shared routes with private views
          AngularDistAssets.angularApp(ng.headerload, ng.postload.map(_(request)))
      }
    }
  }

  def route[T](request: MaybeUserRequest[T]): Routeable = {
    angularRouter.route(request)
  }

}

@Singleton
class AngularRouter @Inject() (
    userCommander: UserCommander,
    pageMetaTagsCommander: PageMetaTagsCommander,
    libraryCommander: LibraryCommander,
    airbrake: AirbrakeNotifier,
    libraryMetadataCache: LibraryMetadataCache,
    userMetadataCache: UserMetadataCache) {

  import AngularRouter.Path

  def route(request: MaybeUserRequest[_]): Routeable = {
    val path = AngularRouter.Path(request.path)
    ngLoginRedirects.get(path.path) map { toPath =>
      request match {
        case _: UserRequest[_] => MovedPermanentlyRoute(toPath)
        case _ => RedirectToLogin(toPath)
      }
    } orElse {
      if (path.primary == "friends" || path.path == "/connections") {
        request match {
          case r: UserRequest[_] => Some(SeeOtherRoute(s"/${URLEncoder.encode(r.user.username.value, UTF8)}/connections"))
          case _ => Some(RedirectToLogin("/connections"))
        }
      } else {
        ngStaticPage(path, request.userOpt.isDefined) orElse userOrLibrary(path, request)
      }
    } getOrElse {
      Error404
    }
  }

  private val ngLoginRedirects = Map(
    "/recommendations" -> "/",
    "/friends/invite" -> "/invite"
  )
  private val ngFixedRoutes: Map[String, Seq[MaybeUserRequest[_] => Future[String]]] = Map(
    "/" -> Seq(), // Note: "/" currently handled directly by HomeController.home
    "/invite" -> Seq(),
    "/profile" -> Seq(),
    "/find" -> Seq(),
    "/tags/manage" -> Seq(),
    "/keeps" -> Seq()
  )
  private val ngPrefixRoutes: Map[String, Seq[MaybeUserRequest[_] => Future[String]]] = Map(
    "keep" -> Seq(),
    "tag" -> Seq()
  )

  //private val dataOnEveryAngularPage = Seq(injectUser _) // todo: Have fun with this!

  // def injectUser(request: MaybeUserRequest[_]) = Future {
  //   "" // inject user data!
  // }

  private def ngStaticPage(path: Path, loggedIn: Boolean): Option[Routeable] = {
    // Right now, all static routes are for users only. If this changes, update this!
    ngFixedRoutes.get(path.path) orElse ngPrefixRoutes.get(path.primary) map { dataLoaders =>
      if (loggedIn) {
        Angular(None, dataLoaders)
      } else {
        RedirectToLogin(path.path)
      }
    }
  }

  // combined to re-use User lookup
  private def userOrLibrary(path: Path, request: MaybeUserRequest[_]): Option[Routeable] = {
    if (path.primary.toLowerCase == "me") {
      request.userOpt.map { user =>
        SeeOtherRoute("/" + (user.username.value +: path.segments.drop(1)).map(r => URLEncoder.encode(r, UTF8)).mkString("/"))
      } orElse {
        Some(RedirectToLogin(path.path))
      }
    } else if (path.primary.nonEmpty) {
      userCommander.getUserByUsernameOrAlias(Username(path.primary)).flatMap {
        case (user, isUserAlias) =>
          if (user.username.value != path.primary) { // user moved or username normalization
            val redir = "/" + (user.username.value +: path.segments.drop(1)).map(r => URLEncoder.encode(r, UTF8)).mkString("/")
            if (isUserAlias) Some(MovedPermanentlyRoute(redir)) else Some(SeeOtherRoute(redir))
          } else if (path.segments.length == 1) { // user profile page
            Some(Angular(Some(userMetadata(user))))
          } else if (path.segments.length == 2 && (path.segments(1) == "libraries" || path.segments(1) == "connections" || path.segments(1) == "followers")) { // user profile page (Angular will rectify /libraries)
            Some(Angular(Some(userMetadata(user))))
          } else if (path.segments.length == 3 && path.segments(1) == "libraries" && (path.segments(2) == "following" || path.segments(2) == "invited")) { // user profile page (nested routes)
            Some(Angular(Some(userMetadata(user))))
          } else {
            path.segments.tail.headOption.flatMap { secondary =>
              libraryCommander.getLibraryBySlugOrAlias(user.id.get, LibrarySlug(secondary)).map {
                case (library, isLibraryAlias) =>
                  if (isLibraryAlias) { // library moved
                    val redir = libraryCommander.getLibraryPath(library).split("/").map(r => URLEncoder.encode(r, UTF8)).mkString("/")
                    Some(MovedPermanentlyRoute(redir))
                  } else if (library.slug.value != secondary) { // slug normalization
                    val redir = "/" + (path.segments.dropRight(1) :+ library.slug.value).map(r => URLEncoder.encode(r, UTF8)).mkString("/")
                    Some(SeeOtherRoute(redir))
                  } else {
                    val metadata = if (request.userAgentOpt.getOrElse(UserAgent.UnknownUserAgent).possiblyBot) {
                      Some(libMetadata(library))
                    } else None
                    Some(Angular(metadata)) // great place to postload request data since we have `lib` available
                  }
              } getOrElse None
            }
          }
      }
    } else {
      None
    }
  }

  private def userMetadata(user: User): Future[String] = try {
    userMetadataCache.getOrElseFuture(UserMetadataKey(user.id.get)) {
      pageMetaTagsCommander.userMetaTags(user).imap(_.formatOpenGraphForUser)
    }
  } catch {
    case e: Throwable =>
      airbrake.notify(s"on getting library metadata for $user", e)
      Future.successful("")
  }

  private def libMetadata(library: Library): Future[String] = try {
    libraryMetadataCache.getOrElseFuture(LibraryMetadataKey(library.id.get)) {
      pageMetaTagsCommander.libraryMetaTags(library).imap(_.formatOpenGraphForLibrary)
    }
  } catch {
    case e: Throwable =>
      airbrake.notify(s"on getting library metadata for $library", e)
      Future.successful("")
  }

}

object AngularRouter {
  case class Path(path: String) {
    val segments = path.drop(1).split('/').map(URLDecoder.decode(_, UTF8))
    val primary = segments.head
  }
}
