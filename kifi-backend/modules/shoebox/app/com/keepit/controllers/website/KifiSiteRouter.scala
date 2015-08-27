package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.cache.TransactionalCaching.Implicits._
import com.keepit.common.controller._
import com.keepit.common.core._
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.http._
import com.keepit.common.mail.KifiMobileAppLinkFlag
import com.keepit.heimdal.{ HeimdalContextBuilderFactory, HeimdalContextBuilder }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
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
  libraryFetchCommander: LibraryFetchCommander,
  libPathCommander: PathCommander,
  orgInviteCommander: OrganizationInviteCommander,
  libraryMetadataCache: LibraryMetadataCache,
  userMetadataCache: UserMetadataCache,
  applicationConfig: FortyTwoConfig,
  organizationAnalytics: OrganizationAnalytics,
  airbrake: AirbrakeNotifier,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
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

  private def redirectUserToProfileToConnect(friend: Option[String], request: MaybeUserRequest[_]): Option[Result] = {
    // for old emails
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
    lookupUser(Handle.fromUsername(username)) map {
      case (user, redirectStatusOpt) =>
        redirectStatusOpt map { status =>
          Redirect(s"/${user.username.urlEncoded}${dropPathSegment(request.uri)}", status)
        } getOrElse {
          AngularApp.app(() => userMetadata(user, UserProfileTab(request.path)))
        }
    } getOrElse notFound(request)
  }

  def serveWebAppIfOrganizationFound(handle: OrganizationHandle) = WebAppPage { implicit request =>
    lookupOrganization(handle) map {
      case (org, redirectStatusOpt) =>
        redirectStatusOpt map { status =>
          val foundHandle = Handle.fromOrganizationHandle(org.handle)
          Redirect(s"/${foundHandle.urlEncoded}${dropPathSegment(request.uri)}", status)
        } getOrElse {
          AngularApp.app()
        }
    } getOrElse notFound(request)
  }

  def serveWebAppIfHandleFound(handle: Handle) = WebAppPage { implicit request =>
    lookupByHandle(handle) map {
      case (handleOwner, redirectStatusOpt) =>
        redirectStatusOpt map { status =>
          val foundHandle = handleOwner match {
            case Left(org) => Handle.fromOrganizationHandle(org.handle)
            case Right(user) => Handle.fromUsername(user.username)
          }
          Redirect(s"/${foundHandle.urlEncoded}${dropPathSegment(request.uri)}", status)
        } getOrElse {
          AngularApp.app()
        }
    } getOrElse notFound(request)
  }

  def serveWebAppIfUserIsSelf(username: Username) = WebAppPage { implicit request =>
    request match {
      case r: UserRequest[_] =>
        lookupUser(Handle.fromUsername(username)).filter { case (user, _) => user.id == r.user.id } map {
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
    lookupByHandle(handle) flatMap {
      case (handleOwner, spaceRedirectStatusOpt) =>
        val handleSpace: LibrarySpace = handleOwner match {
          case Left(org) => org.id.get
          case Right(user) => user.id.get
        }
        val libraryOpt = libraryFetchCommander.getLibraryBySlugOrAlias(handleSpace, LibrarySlug(slug))
        libraryOpt map {
          case (library, isLibraryAlias) =>
            val libraryHasBeenMoved = isLibraryAlias
            val handleOwnerChangedTheirHandle = spaceRedirectStatusOpt.contains(MOVED_PERMANENTLY)
            val wasLibrarySlugNormalized = !libraryHasBeenMoved && library.slug.value != slug
            val wasHandleNormalized = spaceRedirectStatusOpt.contains(SEE_OTHER)

            if (libraryHasBeenMoved || handleOwnerChangedTheirHandle || wasLibrarySlugNormalized || wasHandleNormalized) {
              val uri = libPathCommander.getPathForLibraryUrlEncoded(library) + dropPathSegment(dropPathSegment(request.uri))

              val status = if (handleOwnerChangedTheirHandle || libraryHasBeenMoved) {
                MOVED_PERMANENTLY
              } else {
                SEE_OTHER
              }

              Redirect(uri, status)
            } else {
              AngularApp.app(() => libMetadata(library))
            }
        }
    } getOrElse notFound(request)
  }

  private def lookupUser(handle: Handle) = {
    lookupByHandle(handle) flatMap {
      case (Left(org), _) => None
      case (Right(user), redirectStatusOpt) => Some((user, redirectStatusOpt))
    }
  }

  private def lookupOrganization(handle: Handle) = {
    lookupByHandle(handle) flatMap {
      case (Right(user), _) => None
      case (Left(org), redirectStatusOpt) => Some((org, redirectStatusOpt))
    }
  }

  private def lookupByHandle(handle: Handle): Option[(Either[Organization, User], Option[Int])] = {
    val handleOwnerOpt = db.readOnlyMaster { implicit session => handleCommander.getByHandle(handle) }
    handleOwnerOpt.map {
      case (handleOwner, isPrimary) =>
        val foundHandle = handleOwner match {
          case Left(org) => Handle.fromOrganizationHandle(org.handle)
          case Right(user) => Handle.fromUsername(user.username)
        }

        if (foundHandle != handle) {
          // owner moved or handle normalization
          (handleOwner, Some(if (!isPrimary) MOVED_PERMANENTLY else SEE_OTHER))
        } else {
          (handleOwner, None)
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
            lookupUser(Username(request.path.drop(1))) map { case (u, _) => u.externalId }
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
