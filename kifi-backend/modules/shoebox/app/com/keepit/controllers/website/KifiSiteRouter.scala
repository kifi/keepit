package com.keepit.controllers.website

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
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import play.api.Play
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.{ ActionFilter, Result }
import securesocial.core.SecureSocial

import scala.concurrent.Future

@Singleton // for performance
class KifiSiteRouter @Inject() (
  db: Database,
  userRepo: UserRepo,
  userCommander: UserCommander,
  handleCommander: HandleCommander,
  val userIpAddressCommander: UserIpAddressCommander,
  pageMetaTagsCommander: PageMetaTagsCommander,
  libraryCommander: LibraryCommander,
  libPathCommander: LibraryPathCommander,
  libraryMetadataCache: LibraryMetadataCache,
  userMetadataCache: UserMetadataCache,
  applicationConfig: FortyTwoConfig,
  airbrake: AirbrakeNotifier,
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
    case r: UserRequest[_] => Redirect(s"/${r.user.username.urlEncoded}$subpath")
    case r: NonUserRequest[_] => redirectToLogin(s"/me$subpath", r)
  }

  def redirectFromFriends(friend: Option[String]) = WebAppPage { implicit request => // for old emails
    redirectUserToProfileToConnect(friend, request) getOrElse redirUserToOwnProfile("/connections", request)
  }
  def handleInvitePage(friend: Option[String]) = WebAppPage { implicit request =>
    redirectUserToProfileToConnect(friend, request) getOrElse serveWebAppToUser2
  }
  private def redirectUserToProfileToConnect(friend: Option[String], request: MaybeUserRequest[_]): Option[Result] = { // for old emails
    friend.flatMap(ExternalId.asOpt[User]) flatMap { userExtId =>
      db.readOnlyMaster { implicit session =>
        userRepo.getOpt(userExtId)
      }
    } map { user =>
      val url = s"/${user.username.urlEncoded}?intent=connect"
      request match {
        case _: UserRequest[_] => Redirect(url)
        case r: NonUserRequest[_] => redirectToLogin(url, r)
      }
    }
  }

  def serveWebAppToUser = WebAppPage(implicit request => serveWebAppToUser2)
  private def serveWebAppToUser2(implicit request: MaybeUserRequest[_]): Result = request match {
    case ur: UserRequest[_] => {
      userIpAddressCommander.logUserByRequest(ur)
      AngularApp.app()
    }
    case r: NonUserRequest[_] => redirectToLogin(r.uri, r)
  }

  def serveWebAppIfUserFound(username: Username) = WebAppPage { implicit request =>
    lookupUsername(username) map {
      case (user, redirectStatusOpt) =>
        redirectStatusOpt map { status =>
          Redirect(s"/${user.username.urlEncoded}${dropPathSegment(request.uri)}", status)
        } getOrElse {
          AngularApp.app(() => userMetadata(user, UserProfileTab(request.path)))
        }
    } getOrElse notFound(request)
  }

  def serveWebAppIfUserIsSelf(username: Username) = WebAppPage { implicit request =>
    request match {
      case r: UserRequest[_] =>
        lookupUsername(username).filter { case (user, _) => user.id == r.user.id } map {
          case (user, redirectStatusOpt) =>
            redirectStatusOpt map { status =>
              Redirect(s"/${user.username.urlEncoded}${dropPathSegment(request.uri)}", status)
            } getOrElse AngularApp.app()
        } getOrElse notFound(request)
      case r: NonUserRequest[_] =>
        redirectToLogin(s"/me${dropPathSegment(request.uri)}", r)
    }
  }

  def serveWebAppIfLibraryFound(handle: Handle, slug: String) = WebAppPage { implicit request =>
    lookupHandle(handle) flatMap {
      case (handleOwner, userRedirectStatusOpt) =>
        val libraryOpt = handleOwner match {
          case Left(org) => libraryCommander.getLibraryBySlugOrAlias(org.id.get, LibrarySlug(slug))
          case Right(user) => libraryCommander.getLibraryBySlugOrAlias(user.id.get, LibrarySlug(slug))
        }
        libraryOpt map {
          case (library, isLibraryAlias) =>
            if (library.slug.value != slug || userRedirectStatusOpt.isDefined) { // library moved
              val uri = libPathCommander.getPathUrlEncoded(library) + dropPathSegment(dropPathSegment(request.uri))
              val status = if (!isLibraryAlias || userRedirectStatusOpt.contains(303)) 303 else 301
              Redirect(uri, status)
            } else {
              AngularApp.app(() => libMetadata(library))
            }
        }
    } getOrElse notFound(request)
  }

  private def lookupHandle(handle: Handle): Option[(Either[Organization, User], Option[Int])] = {
    val handleOwnerOpt = db.readOnlyMaster { implicit session => handleCommander.getByHandle(handle) }
    handleOwnerOpt map {
      case (handleOwner, isPrimary) =>
        val foundHandle = handleOwner match {
          case Left(org) => Handle.fromOrganizationHandle(org.getHandle)
          case Right(user) => Handle.fromUsername(user.username)
        }
        if (foundHandle != handle) { // user moved or username normalization
          (handleOwner, Some(if (!isPrimary) 301 else 303))
        } else {
          (handleOwner, None)
        }
    }
  }
  private def lookupUsername(username: Username): Option[(User, Option[Int])] = {
    userCommander.getUserByUsername(username) map {
      case (user, isPrimary) =>
        if (user.username != username) { // user moved or username normalization
          (user, Some(if (!isPrimary) 301 else 303))
        } else {
          (user, None)
        }
    }
  }

  private def dropPathSegment(uri: String): String = uri.drop(1).dropWhile(!"/?".contains(_))

  private def redirectToLogin(url: String, request: NonUserRequest[_]): Result = {
    Redirect("/login").withSession(request.session + (SecureSocial.OriginalUrlKey -> url))
  }

  private def notFound(request: MaybeUserRequest[_]): Result = {
    NotFound(views.html.error.notFound(request.path))
  }

  private object MobileAppFilter extends ActionFilter[MaybeUserRequest] {
    protected def filter[A](request: MaybeUserRequest[A]): Future[Option[Result]] = Future.successful {
      if (request.userAgentOpt.exists(_.isMobile) &&
        request.queryString.get(KifiMobileAppLinkFlag.key).exists(_.contains(KifiMobileAppLinkFlag.value))) {
        val uri = Some(request).filter(r => r.path.length > 1 && r.path.indexOf('/', 1) == -1 && r.queryString.get("intent").exists(_.contains("connect"))) flatMap { req =>
          req.queryString.get("id").flatMap(_.headOption).flatMap(ExternalId.asOpt[User]) orElse {
            lookupUsername(Username(request.path.drop(1))) map { case (u, _) => u.externalId }
          } map { userExtId: ExternalId[User] =>
            req.queryString.get("invited") match {
              case Some(_) => s"/friends?friend=${userExtId.id}"
              case None => s"/invite?friend=${userExtId.id}"
            }
          }
        } getOrElse request.uri
        Some(Ok(views.html.mobile.mobileAppRedirect(uri)))
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
