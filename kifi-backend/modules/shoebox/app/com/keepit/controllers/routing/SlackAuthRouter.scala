package com.keepit.controllers.routing

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.core.optionExtensionOps
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.controllers.core.PostRegIntent
import com.keepit.controllers.website.DeepLinkRedirect
import com.keepit.discussion.Message
import com.keepit.heimdal.{ HeimdalServiceClient, HeimdalContextBuilderFactory }
import com.keepit.model._
import com.keepit.shoebox.controllers.TrackingActions
import com.keepit.slack.{ SlackAnalytics, SlackTeamCommander }
import com.keepit.slack.models._
import com.keepit.slack.models.{ SlackTeamId, SlackTeamMembershipRepo, SlackTeamRepo }
import play.api.mvc.{ Cookie, Result }
import securesocial.core.SecureSocial
import views.html
import com.keepit.common.core._

import scala.concurrent.ExecutionContext

@Singleton
class SlackAuthRouter @Inject() (
  db: Database,
  userRepo: UserRepo,
  orgRepo: OrganizationRepo,
  libraryRepo: LibraryRepo,
  keepRepo: KeepRepo,
  uriRepo: NormalizedURIRepo,
  slackTeamRepo: SlackTeamRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  permissionCommander: PermissionCommander,
  pathCommander: PathCommander,
  slackTeamCommander: SlackTeamCommander,
  heimdal: HeimdalServiceClient,
  val heimdalContextBuilder: HeimdalContextBuilderFactory,
  val airbrake: AirbrakeNotifier,
  val userActionsHelper: UserActionsHelper,
  implicit val slackAnalytics: SlackAnalytics,
  implicit val ec: ExecutionContext,
  implicit val publicIdConfiguration: PublicIdConfiguration)
    extends ShoeboxServiceController with UserActions with TrackingActions {

  def fromSlackToInstallPage(slackTeamId: SlackTeamId) = (MaybeUserAction andThen SlackClickTracking(Some(slackTeamId), "extension")) { implicit request =>
    val redir = db.readOnlyMaster { implicit s =>
      slackTeamRepo.getBySlackTeamId(slackTeamId).flatMap(_.organizationId).map(orgRepo.get).filter(_.isActive).map { org =>
        val target = PathCommander.browserExtension.absolute
        weWantThisUserToAuthWithSlack(request.userIdOpt, org, slackTeamId) match {
          case true => redirectThroughSlackAuth(org, slackTeamId, target)
          case false => Redirect(target)
        }
      }
    }
    redir.getOrElse(notFound(request))
  }

  def fromSlackToUser(slackTeamId: SlackTeamId, extId: ExternalId[User], isWelcomeMessage: Boolean) = (MaybeUserAction andThen SlackClickTracking(Some(slackTeamId), "user")) { implicit request =>
    import com.keepit.common.core._
    db.readOnlyMaster { implicit s =>
      val userPathOpt = userRepo.getOpt(extId).filter(_.isActive).map(user => pathCommander.pathForUser(user).absolute)
      (for {
        _ <- Some(true) if request.userIdOpt.isEmpty || isWelcomeMessage
        userPath <- userPathOpt
        orgId <- slackTeamRepo.getBySlackTeamId(slackTeamId).flatMap(_.organizationId)
        org <- orgRepo.getActive(orgId).tap {
          case None => airbrake.notify(s"[inactive-org] slackTeam=${slackTeamId.value} references inactive org=${orgId.id}")
          case _ =>
        }
        _ <- Some(true) if weWantThisUserToAuthWithSlack(request.userIdOpt, org, slackTeamId)
      } yield redirectThroughSlackAuth(org, slackTeamId, userPath, userId = Some(extId))) orElse userPathOpt.map(Redirect(_))
    }.getOrElse(notFound(request))
  }

  def fromSlackToOrg(slackTeamId: SlackTeamId, pubId: PublicId[Organization]) = (MaybeUserAction andThen SlackClickTracking(Some(slackTeamId), "org")) { implicit request =>
    val redir = db.readOnlyMaster { implicit s =>
      Organization.decodePublicId(pubId).toOption.flatMap(orgId => Some(orgRepo.get(orgId)).filter(_.isActive)).map { org =>
        val target = pathCommander.orgPage(org).absolute
        weWantThisUserToAuthWithSlack(request.userIdOpt, org, slackTeamId) match {
          case true => redirectThroughSlackAuth(org, slackTeamId, target)
          case false => Redirect(target)
        }
      }
    }
    redir.getOrElse(notFound(request))
  }
  def fromSlackToOrgIntegrations(slackTeamId: SlackTeamId, pubId: PublicId[Organization]) = (MaybeUserAction andThen SlackClickTracking(Some(slackTeamId), "integrations")) { implicit request =>
    val redir = db.readOnlyMaster { implicit s =>
      Organization.decodePublicId(pubId).toOption.flatMap(orgId => Some(orgRepo.get(orgId)).filter(_.isActive)).map { org =>
        val target = pathCommander.orgIntegrationsPage(org).absolute + "#slack-settings-" // Carlos magic to smooth-scroll to the settings part
        weWantThisUserToAuthWithSlack(request.userIdOpt, org, slackTeamId) match {
          case true => redirectThroughSlackAuth(org, slackTeamId, target)
          case false => Redirect(target)
        }
      }
    }
    redir.getOrElse(notFound(request))
  }

  def fromSlackToLibrary(slackTeamId: SlackTeamId, pubId: PublicId[Library]) = (MaybeUserAction andThen SlackClickTracking(Some(slackTeamId), "library")) { implicit request =>
    val redir = db.readOnlyMaster { implicit s =>
      Library.decodePublicId(pubId).toOption.flatMap(libId => Some(libraryRepo.get(libId)).filter(_.isActive)).map { lib =>
        val target = pathCommander.libraryPage(lib).absolute
        (for {
          org <- lib.organizationId.map(orgRepo.get).filter(_.isActive)
          _ <- Some(true) if weWantThisUserToAuthWithSlack(request.userIdOpt, org, slackTeamId)
        } yield redirectThroughSlackAuth(org, slackTeamId, target, libraryId = Some(pubId))) getOrElse Redirect(target)
      }
    }
    redir.getOrElse(notFound(request))
  }

  def fromSlackToOwnFeed(slackTeamId: SlackTeamId) = (MaybeUserAction andThen SlackClickTracking(Some(slackTeamId), "ownFeed")) { implicit request =>
    db.readOnlyMaster { implicit s =>
      val target = pathCommander.ownKeepsFeedPage.absolute
      (for {
        org <- slackTeamRepo.getBySlackTeamId(slackTeamId).flatMap(_.organizationId).map(orgRepo.get).filter(_.isActive)
        _ <- Some(true) if weWantThisUserToAuthWithSlack(request.userIdOpt, org, slackTeamId)
      } yield redirectThroughSlackAuth(org, slackTeamId, target)) getOrElse Redirect(target)
    }
  }

  private def redirectWithKeep(requesterId: Option[Id[User]], slackTeamId: SlackTeamId, pubId: PublicId[Keep], viewArticle: Boolean)(implicit session: RSession, request: MaybeUserRequest[_]): Option[Result] = {
    for {
      keepId <- Keep.decodePublicId(pubId).airbrakingOption
      keep <- Some(keepRepo.get(keepId)) if keep.isActive
      found <- {
        def keepPageUrl = pathCommander.pathForKeep(keep).absolute
        val permissions = permissionCommander.getKeepPermissions(keep.id.get, requesterId)

        if (permissions.contains(KeepPermission.VIEW_KEEP)) Some {
          // Authorized keep
          val noExtUrl = if (viewArticle) keep.url else keepPageUrl
          if (permissions.contains(KeepPermission.ADD_MESSAGE)) {
            Ok(html.maybeExtDeeplink(DeepLinkRedirect(url = keep.url, externalLocator = Some(s"/messages/${pubId.id}")), noExtUrl = noExtUrl))
          } else {
            // todo: we should be able to open the thread in the extension in read-only mode
            Redirect(noExtUrl)
          }
        }
        else {
          // Unauthorized keep
          if (viewArticle) Some(Redirect(keep.url)) // todo: should this leak the keep.url or should we defer to the url hash?
          else for {
            slackTeam <- slackTeamRepo.getBySlackTeamId(slackTeamId)
            orgId <- slackTeam.organizationId
          } yield {
            val org = orgRepo.get(orgId)
            redirectThroughSlackAuth(org, slackTeamId, keepPageUrl, keepId = Some(pubId))
          }
        }
      }
    } yield found
  }

  private def redirectWithUrlHash(urlHash: UrlHash, viewArticle: Boolean)(implicit session: RSession): Option[Result] = {
    if (viewArticle) uriRepo.getByUrlHash(urlHash).map(uri => Redirect(uri.url)) // todo: we're hashing keep.url, so this might not work as expected with normalization
    else None
  }

  // todo: should SlackClickTracking reports viewArticle vs replyToThread
  def fromSlackToKeep(slackTeamId: SlackTeamId, pubId: PublicId[Keep], urlHash: UrlHash, viewArticle: Boolean) = (MaybeUserAction andThen SlackClickTracking(Some(slackTeamId), "keep")) { implicit request =>
    db.readOnlyMaster { implicit s =>
      redirectWithKeep(request.userIdOpt, slackTeamId, pubId, viewArticle) orElse redirectWithUrlHash(urlHash, viewArticle) getOrElse notFound(request)
    }
  }

  def fromSlackToMessage(slackTeamId: SlackTeamId, keepId: PublicId[Keep], urlHash: UrlHash, msgId: PublicId[Message]) = {
    fromSlackToKeep(slackTeamId, keepId, urlHash, viewArticle = true)
  }

  def togglePersonalDigest(slackTeamId: SlackTeamId, slackUserId: SlackUserId, hash: String, turnOn: Boolean) = (MaybeUserAction andThen SlackClickTracking(Some(slackTeamId), "toggleDigest")) { implicit request =>
    if (SlackTeamMembership.decodeTeamAndUser(hash).safely.contains((slackTeamId, slackUserId))) {
      slackTeamCommander.unsafeTogglePersonalDigests(slackTeamId, slackUserId, turnOn = turnOn) // this can fail if there is no such membership! (should never happen)
    }
    Redirect(PathCommander.home.absolute)
  }

  private def weWantThisUserToAuthWithSlack(userIdOpt: Option[Id[User]], org: Organization, slackTeamId: SlackTeamId)(implicit session: RSession): Boolean = {
    userIdOpt match {
      case None => true // always hand non-users over to the frontend to ask them to log in or sign up
      case Some(userId) => // if they're logged in AND they can't access the page in question AND signing up with slack would help
        slackTeamRepo.getBySlackTeamId(slackTeamId).exists { slackTeam =>
          val orgIsConnectedToThisSlackTeam = slackTeam.organizationId.safely.contains(org.id.get)
          val userIsNotInThisOrg = orgMembershipRepo.getByOrgIdAndUserId(org.id.get, userId).isEmpty
          val userHasNotGivenUsTheirSlackInfo = slackTeamMembershipRepo.getByUserIdAndSlackTeam(userId, slackTeamId).isEmpty
          orgIsConnectedToThisSlackTeam && (userIsNotInThisOrg && userHasNotGivenUsTheirSlackInfo)
        }
    }
  }

  private def redirectThroughSlackAuth(org: Organization, slackTeamId: SlackTeamId, url: String, keepId: Option[PublicId[Keep]] = None, libraryId: Option[PublicId[Library]] = None, userId: Option[ExternalId[User]] = None)(implicit request: MaybeUserRequest[_]): Result = {
    val modelParams = (keepId, libraryId, userId) match {
      case (Some(keepId), _, _) => s"signUpWithSlack=keep&keepId=${keepId.id}"
      case (_, Some(libraryId), _) => s"signUpWithSlack=library&libraryId=${libraryId.id}"
      case (_, _, Some(userId)) => s"signUpWithSlack=welcome&userId=${userId.id}"
      case _ => "signUpWithSlack=true"
    }

    val slackAuthPage = pathCommander.orgPage(org) + s"?$modelParams&slackTeamId=${slackTeamId.value}"

    Redirect(slackAuthPage.absolute).withSession(request.session + (SecureSocial.OriginalUrlKey -> url)).withCookies(Cookie(PostRegIntent.onFailUrlKey, slackAuthPage.absolute))
  }

  private def notFound(request: MaybeUserRequest[_]): Result = {
    NotFound(views.html.error.notFound(request.path))
  }
}
