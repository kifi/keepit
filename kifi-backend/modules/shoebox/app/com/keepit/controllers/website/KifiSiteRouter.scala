package com.keepit.controllers.website

import java.net.{ URLDecoder, URLEncoder }

import com.keepit.common.cache.TransactionalCaching.Implicits._
import com.google.inject.{ Provider, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.core._
import com.keepit.common.controller._
import com.keepit.common.db.ExternalId
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
import play.api.mvc.{ ActionFilter, Result }
import securesocial.core.SecureSocial

import scala.concurrent.Future

sealed trait Routeable
private case class MovedPermanentlyRoute(url: String) extends Routeable
private case class Angular(headerload: Option[Future[String]], postload: Seq[MaybeUserRequest[_] => Future[String]] = Seq.empty) extends Routeable
private case class SeeOtherRoute(url: String) extends Routeable
private case class RedirectToLogin(url: String) extends Routeable
private case object Error404 extends Routeable

@Singleton // for performance
class KifiSiteRouter @Inject() (
  db: Database,
  userRepo: UserRepo,
  angularRouter: AngularRouter,
  applicationConfig: FortyTwoConfig,
  val userActionsHelper: UserActionsHelper)
    extends UserActions with ShoeboxServiceController {

  def redirectUserTo(path: String) = WebAppPage { implicit request =>
    request match {
      case _: UserRequest[_] => Redirect(path)
      case r: NonUserRequest[_] => redirectToLogin(path, r)
    }
  }

  def redirectUserToOwnProfile(subpath: String) = WebAppPage(implicit request => redirUserToOwnProfile(subpath, request))
  private def redirUserToOwnProfile(subpath: String, request: MaybeUserRequest[_]): Result = request match {
    case r: UserRequest[_] => Redirect(s"/${URLEncoder.encode(r.user.username.value, UTF8)}$subpath")
    case r: NonUserRequest[_] => redirectToLogin(s"/me$subpath", r)
  }

  def redirectFromFriends(friend: Option[String]) = WebAppPage { implicit request =>
    redirectUserToProfileToConnect(friend, request) getOrElse redirUserToOwnProfile("/connections", request)
  }
  def handleInvitePage(friend: Option[String]) = WebAppPage { implicit request =>
    redirectUserToProfileToConnect(friend, request) getOrElse serveWebAppToUser2(request)
  }
  private def redirectUserToProfileToConnect(friend: Option[String], request: MaybeUserRequest[_]): Option[Result] = {
    friend.flatMap(ExternalId.asOpt[User]) flatMap { userExtId =>
      db.readOnlyMaster { implicit session =>
        userRepo.getOpt(userExtId)
      }
    } map { user =>
      val url = s"/${URLEncoder.encode(user.username.value, UTF8)}?intent=connect"
      request match {
        case _: UserRequest[_] => Redirect(url)
        case r: NonUserRequest[_] => redirectToLogin(url, r)
      }
    }
  }

  def serveWebAppToUser = WebAppPage(implicit request => serveWebAppToUser2(request))
  private def serveWebAppToUser2(request: MaybeUserRequest[_]): Result = request match {
    case r: UserRequest[_] => serveWebApp(Angular(None), r)
    case r: NonUserRequest[_] => redirectToLogin(r.uri, r)
  }

  // Useful to route anything that a) serves the Angular app, b) requires context about if a user is logged in or not
  def app(path: String) = WebAppPage(implicit r => routeToResult)

  def routeToResult[T](implicit request: MaybeUserRequest[T]): Result = {
    route match {
      case Error404 => NotFound(views.html.error.notFound(request.path))
      case SeeOtherRoute(url) => Redirect(url)
      case MovedPermanentlyRoute(url) => MovedPermanently(url)
      case RedirectToLogin(url) => redirectToLogin(url, request.asInstanceOf[NonUserRequest[_]])
      case ng: Angular => serveWebApp(ng, request)
    }
  }

  def route[T](implicit request: MaybeUserRequest[T]): Routeable = angularRouter.route(request)

  private def redirectToLogin(url: String, request: NonUserRequest[_]): Result = {
    Redirect("/login").withSession(request.session + (SecureSocial.OriginalUrlKey -> url))
  }

  private def serveWebApp(ng: Angular, request: MaybeUserRequest[_]): Result = {
    AngularDistAssets.angularApp(ng.headerload, ng.postload.map(_(request)))
  }

  private object MobileAppFilter extends ActionFilter[MaybeUserRequest] {
    protected def filter[A](request: MaybeUserRequest[A]): Future[Option[Result]] = Future.successful {
      if (request.userAgentOpt.exists(_.isMobile) &&
        request.queryString.get(KifiMobileAppLinkFlag.key).exists(_.contains(KifiMobileAppLinkFlag.value))) {
        Some(Ok(views.html.mobile.MobileRedirect(request.uri)))
      } else None
    }
  }

  private object IncompleteSignupFilter extends ActionFilter[MaybeUserRequest] {
    protected def filter[A](request: MaybeUserRequest[A]): Future[Option[Result]] = Future.successful {
      if (request.userOpt.isEmpty && request.identityOpt.isDefined) {
        Some(Redirect(com.keepit.controllers.core.routes.AuthController.signupPage()))
      } else None
    }
  }

  private val WebAppPage = MaybeUserPage andThen MobileAppFilter andThen IncompleteSignupFilter

}

@Singleton // for performance
class AngularRouter @Inject() (
    db: Database,
    userRepo: UserRepo,
    userCommander: UserCommander,
    pageMetaTagsCommander: PageMetaTagsCommander,
    libraryCommander: LibraryCommander,
    airbrake: AirbrakeNotifier,
    libraryMetadataCache: LibraryMetadataCache,
    userMetadataCache: UserMetadataCache) {

  import AngularRouter.Path

  def route(request: MaybeUserRequest[_]): Routeable = {
    userOrLibrary(Path(request.path), request) getOrElse Error404
  }

  // combined to re-use User lookup
  private def userOrLibrary(path: Path, request: MaybeUserRequest[_]): Option[Routeable] = {
    if (path.primary.nonEmpty) {
      userCommander.getUserByUsernameOrAlias(Username(path.primary)).flatMap {
        case (user, isUserAlias) =>
          if (user.username.value != path.primary) { // user moved or username normalization
            val redir = "/" + (user.username.value +: path.segments.drop(1)).map(r => URLEncoder.encode(r, UTF8)).mkString("/")
            if (isUserAlias) Some(MovedPermanentlyRoute(redir)) else Some(SeeOtherRoute(redir))
          } else if (path.segments.length == 1) { // user profile page
            Some(Angular(Some(userMetadata(user, UserProfileTab(path.path)))))
          } else if (path.segments.length == 2 && (path.segments(1) == "libraries" || path.segments(1) == "connections" || path.segments(1) == "followers")) { // user profile page (Angular will rectify /libraries)
            Some(Angular(Some(userMetadata(user, UserProfileTab(path.path)))))
          } else if (path.segments.length == 3 && path.segments(1) == "libraries" && (path.segments(2) == "following" || path.segments(2) == "invited")) { // user profile page (nested routes)
            Some(Angular(Some(userMetadata(user, UserProfileTab(path.path)))))
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

  private def userMetadata(user: User, tab: UserProfileTab): Future[String] = try {
    userMetadataCache.getOrElseFuture(UserMetadataKey(user.id.get, tab)) {
      pageMetaTagsCommander.userMetaTags(user, tab).imap(_.formatOpenGraphForUser)
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
