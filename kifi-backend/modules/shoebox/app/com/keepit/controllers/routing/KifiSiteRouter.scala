package com.keepit.controllers.routing

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.cache.TransactionalCaching.Implicits._
import com.keepit.common.controller._
import com.keepit.common.core._
import com.keepit.common.crypto.{ KifiUrlRedirectHelper, CryptoSupport, PublicIdConfiguration, PublicId }
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.http._
import com.keepit.common.mail.{ EmailAddress, KifiMobileAppLinkFlag }
import com.keepit.common.net.{ Param, Query }
import com.keepit.common.path.Path
import com.keepit.controllers.core.PostRegIntent
import com.keepit.controllers.website.{ AngularApp, DeepLinkRouter }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal.{ TrackingNonUserKind, ContextStringData, HeimdalContextBuilderFactory, EventType, HeimdalContext, HeimdalContextBuilder, UserEvent, NonUserEvent, HeimdalServiceClient }
import com.keepit.model._
import com.keepit.shoebox.path.{ ShortenedPathRepo, ShortenedPath }
import com.keepit.slack.models.{ SlackUserId, SlackTeamId }
import com.keepit.social.{ IdentityHelpers, NonUserKinds }
import play.api.libs.json.{ Json, JsObject }
import play.api.mvc.{ ActionFilter, Result }
import securesocial.core.SecureSocial
import views.html

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Success, Failure, Try }

@Singleton // for performance
class KifiSiteRouter @Inject() (
  db: Database,
  userRepo: UserRepo,
  orgRepo: OrganizationRepo,
  libraryRepo: LibraryRepo,
  libraryMembershipRepo: LibraryMembershipRepo,
  keepRepo: KeepRepo,
  normalizedUriRepo: NormalizedURIRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  handleCommander: HandleCommander,
  val userIpAddressCommander: UserIpAddressCommander,
  pageMetaTagsCommander: PageMetaTagsCommander,
  libraryInfoCommander: LibraryInfoCommander,
  permissionCommander: PermissionCommander,
  libPathCommander: PathCommander,
  shortenedPathRepo: ShortenedPathRepo,
  libraryMetadataCache: LibraryMetadataCache,
  userMetadataCache: UserMetadataCache,
  orgMetadataCache: OrgMetadataCache,
  keepMetadataCache: KeepMetadataCache,
  airbrake: AirbrakeNotifier,
  val userActionsHelper: UserActionsHelper,
  deepLinkRouter: DeepLinkRouter,
  eliza: ElizaServiceClient,
  heimdal: HeimdalServiceClient,
  sitePreloader: SitePreloader,
  val heimdalContextBuilder: HeimdalContextBuilderFactory,
  implicit val defaultContext: ExecutionContext,
  implicit val publicIdConfiguration: PublicIdConfiguration)
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

  def redirectUserToOwnOrg(subpath: String, noTeam: String, fallback: String) = WebAppPage { implicit request =>
    request match {
      case r: NonUserRequest[_] => Redirect(fallback)
      case u: UserRequest[_] =>
        val redirectToOrg = db.readOnlyReplica { implicit session =>
          (orgMembershipRepo.getAllByUserId(u.userId), subpath) match {
            case (mems, "/settings/plan") => mems.filter(_.role == OrganizationRole.ADMIN).map(_.organizationId).headOption.map(orgRepo.get)
            case (mems, "/settings/credits") => mems.sortBy(_.role).map(_.organizationId).lastOption.map(orgRepo.get)
            case (mems, _) => mems.sortBy(_.id).map(_.organizationId).lastOption.map(orgRepo.get)
          }
        }
        redirectToOrg.map { org => Redirect(s"/${org.handle.value}$subpath") } getOrElse Redirect(noTeam)
    }
  }

  def urlRedirect(signedUrl: String, signedTrackingParams: Option[String]) = MaybeUserAction { implicit request =>
    KifiUrlRedirectHelper.parseKifiUrlRedirect(signedUrl, signedTrackingParams) match {
      case None =>
        log.warn(s"[kifiUrlRedirect] unable to parse kifi url redirect signedUrl=$signedUrl, signedParams=$signedTrackingParams")
        Redirect("/")
      case Some((confirmedUrl, trackingParamsOpt)) =>
        trackingParamsOpt.foreach(params => trackUrlRedirect(params))
        Redirect(confirmedUrl)
    }
  }
  def shortenedPathRedirect(sp: String) = MaybeUserAction { implicit request =>
    val redir = ShortenedPath.decodePublicId(PublicId(sp)) match {
      case Failure(fail) =>
        airbrake.notify(s"Bad shortened path: $sp", fail)
        Path.base
      case Success(spId) => db.readOnlyMaster { implicit s =>
        shortenedPathRepo.get(spId).path.relativeWithLeadingSlash
      }
    }
    Redirect(redir)
  }

  private def trackUrlRedirect(trackingParams: Query)(implicit request: MaybeUserRequest[_]): Unit = {
    val paramContext = HeimdalContext(trackingParams.params.map { case Param(k, Some(v)) => k -> ContextStringData(v) }.toMap)
    paramContext.get[String]("eventType").foreach { eventType =>
      val contextBuilder = heimdalContextBuilder()
      contextBuilder.addExistingContext(paramContext)
      contextBuilder.addRequestInfo(request)
      val context = contextBuilder.build
      val event = request.userIdOpt match {
        case None =>
          NonUserEvent(context.get[String]("slackUserId").getOrElse("unknown"), TrackingNonUserKind.slack, context, EventType(eventType)) // needs to be developed if used outside of slack
        case Some(userId) =>
          UserEvent(userId, context, EventType(eventType))
      }
      heimdal.trackEvent(event)
    }
  }

  def generalRedirect(dataStr: String) = MaybeUserAction { implicit request =>
    def parseDataString(in: String): Option[JsObject] = { // We're very permissive here.
      Try {
        Json.parse(in).as[JsObject]
      }.orElse {
        Try(Json.parse(in.replaceAllLiterally("&quot;", "\"")).as[JsObject])
      }.orElse {
        Try(Json.parse(new String(CryptoSupport.fromBase64(in)).trim).as[JsObject])
      }.toOption
    }

    parseDataString(dataStr).flatMap { data =>
      val redir = deepLinkRouter.generateRedirect(data, request)
      redir.map(r => Ok(html.mobile.deepLinkRedirect(r, data)))
    }.getOrElse {
      airbrake.notify(s"[generalRedirect] Could not figure out how to redirect deep-link: $dataStr")
      Redirect("/get")
    }
  }

  def handleInvitePage(friend: Option[String]) = WebAppPage { implicit request =>
    redirectUserToProfileToConnect(friend, request) getOrElse serveWebAppToUser2()
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

  def serveWebAppToUserOrSignup = WebAppPage { implicit request =>
    request match {
      case ur: UserRequest[_] =>
        userIpAddressCommander.logUserByRequest(ur)
        app()
      case r: NonUserRequest[_] => redirectToSignup(r.uri, r)
    }
  }

  def serveWebAppToUser(dependencies: Seq[PreloadRequest] = Seq.empty) = WebAppPage(implicit request => serveWebAppToUser2(dependencies))

  private def serveWebAppToUser2(dependencies: Seq[PreloadRequest] = Seq.empty)(implicit request: MaybeUserRequest[_]): Result = request match {
    case ur: UserRequest[_] =>
      userIpAddressCommander.logUserByRequest(ur)
      app(dependencies).discardingCookies(PostRegIntent.discardingCookies: _*)
    case r: NonUserRequest[_] => redirectToLogin(r.uri, r).discardingCookies(PostRegIntent.discardingCookies: _*)
  }

  def serveWebAppIfUserFound(username: Username) = WebAppPage { implicit request =>
    lookupUser(Handle.fromUsername(username)) map {
      case (user, redirectStatusOpt) =>
        redirectStatusOpt map { status =>
          Redirect(s"/${user.username.urlEncoded}${dropPathSegment(request.uri)}", status)
        } getOrElse {
          app(() => userMetadata(user, UserProfileTab(request.path)))
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
          app(() => orgMetadata(org))
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
          app(() => handleMetadata(handleOwner))
        }
    } getOrElse notFound(request)
  }

  private def handleMetadata(handle: Either[Organization, User]): Future[String] = handle match {
    case Left(org) => orgMetadata(org)
    case Right(user) => userMetadata(user, UserProfileTab.Libraries)
  }

  def serveWebAppIfUserIsSelf(username: Username) = WebAppPage { implicit request =>
    request match {
      case r: UserRequest[_] =>
        lookupUser(Handle.fromUsername(username)).filter { case (user, _) => user.id == r.user.id } map {
          case (user, redirectStatusOpt) =>
            redirectStatusOpt map { status =>
              Redirect(s"/${user.username.urlEncoded}${dropPathSegment(request.uri)}", status)
            } getOrElse app()
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
        val libraryOpt = libraryInfoCommander.getLibraryBySlugOrAlias(handleSpace, LibrarySlug(slug))
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
              app(() => libMetadata(library))
            }
        }
    } getOrElse notFound(request)
  }

  def serveWebAppIfKeepFound(title: String, pubId: PublicId[Keep], authTokenOpt: Option[String]) = WebAppPage.async { implicit request =>
    Keep.decodePublicId(pubId) match {
      case Failure(ex) => Future.successful(notFound(request))
      case Success(keepId) => {
        val hasShoeboxPermission = db.readOnlyReplica(implicit s => permissionCommander.getKeepPermissions(keepId, request.userIdOpt).contains(KeepPermission.VIEW_KEEP))

        val canSeeKeepFut = {
          if (!hasShoeboxPermission && authTokenOpt.isDefined) {
            eliza.keepHasThreadWithAccessToken(keepId, authTokenOpt.get)
          } else Future.successful(hasShoeboxPermission)
        }

        canSeeKeepFut.map { canSeeKeep =>
          val keepOpt = {
            if (canSeeKeep) db.readOnlyReplica { implicit s => keepRepo.getActive(keepId) }
            else None
          }
          keepOpt.map(keep => app(() => keepMetadata(keep))).getOrElse(notFound(request))
        }
      }
    }
  }

  def serveWebAppIfNormalizedUriFound(pubId: PublicId[NormalizedURI], user: Option[String], library: Option[String], email: Option[EmailAddress]) = WebAppPage { implicit request =>
    NormalizedURI.decodePublicId(pubId).map { uriId =>
      db.readOnlyReplica { implicit s => normalizedUriRepo.get(uriId) }
    }
      .map(_ => app())
      .getOrElse(notFound(request))
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

  private def hideHandleOwner(owner: Either[Organization, User]): Boolean = {
    val kifiSupport = Id[User](97543)
    val invisibleUsers = Set(kifiSupport)
    val invisibleOrgs = Set.empty[Id[Organization]]
    owner.left.exists(org => invisibleOrgs.contains(org.id.get)) || owner.right.exists(user => invisibleUsers.contains(user.id.get))
  }
  private def lookupByHandle(handle: Handle): Option[(Either[Organization, User], Option[Int])] = {
    val handleOwnerOpt = db.readOnlyMaster { implicit session => handleCommander.getByHandle(handle).filterNot(x => hideHandleOwner(x._1)) }
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

  private def redirectToSignup(url: String, request: NonUserRequest[_]): Result = {
    Redirect("/signup").withSession(request.session + (SecureSocial.OriginalUrlKey -> url))
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

  private object NonActiveUserFilter extends ActionFilter[MaybeUserRequest] {
    protected def filter[A](request: MaybeUserRequest[A]): Future[Option[Result]] = Future.successful {
      if ((request.userOpt.isEmpty && request.identityId.isDefined) || request.userOpt.exists(user => user.state == UserStates.INCOMPLETE_SIGNUP)) {
        Some(Redirect(com.keepit.controllers.core.routes.AuthController.signupPage()))
      } else if (request.userOpt.exists(user => user.state == UserStates.BLOCKED || user.state == UserStates.INACTIVE)) {
        Some(Redirect("/logout"))
      } else None
    }
  }

  private val WebAppPage = MaybeUserPage andThen MobileAppFilter andThen NonActiveUserFilter

  private def orgMetadata(org: Organization): Future[String] = try {
    orgMetadataCache.getOrElseFuture(OrgMetadataKey(org.id.get)) {
      pageMetaTagsCommander.orgMetaTags(org).imap(_.formatOpenGraphForOrg)
    }
  } catch {
    case e: Throwable =>
      airbrake.notify(s"on getting organization metadata for $org", e)
      Future.successful("")
  }

  private def userMetadata(user: User, tab: UserProfileTab): Future[String] = try {
    userMetadataCache.getOrElseFuture(UserMetadataKey(user.id.get, tab)) {
      pageMetaTagsCommander.userMetaTags(user, tab).imap(_.formatOpenGraphForUser)
    }
  } catch {
    case e: Throwable =>
      airbrake.notify(s"on getting user metadata for $user", e)
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

  private def keepMetadata(keep: Keep): Future[String] = try {
    keepMetadataCache.getOrElseFuture(KeepMetadataKey(keep.id.get)) {
      pageMetaTagsCommander.keepMetaTags(keep).imap(_.formatOpenGraphForKeep)
    }
  } catch {
    case e: Throwable =>
      airbrake.notify(s"on getting keep metadata for keep ${keep.id.get}", e)
      Future.successful("")
  }

  private def app()(implicit request: MaybeUserRequest[_]): Result =
    app(None, Seq.empty)
  private def app(dependencies: Seq[PreloadRequest])(implicit request: MaybeUserRequest[_]): Result =
    app(None, dependencies)
  private def app(metaGenerator: () => Future[String])(implicit request: MaybeUserRequest[_]): Result =
    app(Some(metaGenerator), Seq.empty)
  private def app(metaGenerator: () => Future[String], dependencies: Seq[PreloadRequest])(implicit request: MaybeUserRequest[_]): Result =
    app(Some(metaGenerator), dependencies)
  private def app(metaGeneratorOpt: Option[() => Future[String]], dependencies: Seq[PreloadRequest])(implicit request: MaybeUserRequest[_]): Result = {
    val toPreload = if (request.getQueryString("preload").isDefined) {
      PreloadSet.filter(dependencies)
    } else Seq.empty
    AngularApp.app(metaGeneratorOpt, preload(toPreload))
  }

  private def preload(reqs: Seq[PreloadRequest])(implicit request: MaybeUserRequest[_]) = {
    sitePreloader.preload(reqs)
  }

}
