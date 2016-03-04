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
import com.keepit.common.http._
import com.keepit.common.social.BasicUserRepo
import com.keepit.controllers.core.PostRegIntent
import com.keepit.controllers.website.{ DeepLinkRouter, DeepLinkRedirect }
import com.keepit.model._
import com.keepit.slack.SlackTeamCommander
import com.keepit.slack.models._
import com.keepit.slack.models.{ SlackTeamId, SlackTeamMembershipRepo, SlackTeamRepo }
import play.api.mvc.{ Cookie, Result }
import securesocial.core.SecureSocial
import views.html

import scala.concurrent.{ Future, ExecutionContext }

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
  val airbrake: AirbrakeNotifier,
  val userActionsHelper: UserActionsHelper,
  implicit val defaultContext: ExecutionContext,
  implicit val publicIdConfiguration: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  def fromSlackToInstallPage(slackTeamId: SlackTeamId) = MaybeUserAction { implicit request =>
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

  def fromSlackToUser(slackTeamId: SlackTeamId, extId: ExternalId[User], isWelcomeMessage: Boolean) = MaybeUserAction { implicit request =>
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

  def fromSlackToOrg(slackTeamId: SlackTeamId, pubId: PublicId[Organization]) = MaybeUserAction { implicit request =>
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
  def fromSlackToOrgIntegrations(slackTeamId: SlackTeamId, pubId: PublicId[Organization]) = MaybeUserAction { implicit request =>
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

  def fromSlackToLibrary(slackTeamId: SlackTeamId, pubId: PublicId[Library]) = MaybeUserAction { implicit request =>
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

  def fromSlackToKeep(slackTeamId: SlackTeamId, pubId: PublicId[Keep], urlHash: UrlHash, onKifi: Boolean) = MaybeUserAction { implicit request =>
    // show 3rd party url with ext if possible, otherwise go to keep page (with proper upsells) or 3rd party url
    val redirOpt = db.readOnlyMaster { implicit s =>
      Keep.decodePublicId(pubId)
        .map(keepId => keepRepo.get(keepId)).filter(_.isActive)
        .map { keep =>
          val isLoggedIn = request.userIdOpt.isDefined
          val hasMessagingPermission = permissionCommander.getKeepPermissions(keep.id.get, request.userIdOpt).contains(KeepPermission.ADD_MESSAGE)
          val keepPageUrl = pathCommander.pathForKeep(keep).absolute
          if (!onKifi && hasMessagingPermission) {
            Ok(html.maybeExtDeeplink(DeepLinkRedirect(url = keep.url, externalLocator = Some(s"/messages/${pubId.id}")), noExtUrl = keep.url))
          } else if (onKifi && isLoggedIn && hasMessagingPermission) {
            Ok(html.maybeExtDeeplink(DeepLinkRedirect(url = keep.url, externalLocator = Some(s"/messages/${pubId.id}")), noExtUrl = keepPageUrl))
          } else if (onKifi && !isLoggedIn) {
            val orgIdOpt = keep.organizationId.orElse(slackTeamRepo.getBySlackTeamId(slackTeamId).flatMap(_.organizationId))
            val orgOpt = orgIdOpt.map(orgId => orgRepo.get(orgId))
            orgOpt
              .map(org => redirectThroughSlackAuth(org, slackTeamId, keepPageUrl, keepId = Some(pubId)))
              .getOrElse(Redirect(keepPageUrl))
          } else Redirect(keep.url)
        }
    }
    redirOpt.getOrElse {
      if (onKifi) notFound(request)
      else {
        db.readOnlyMaster { implicit s =>
          uriRepo.getByUrlHash(urlHash)
            .map(uri => Redirect(uri.url))
            .getOrElse(notFound(request))
        }
      }
    }
  }

  def togglePersonalDigest(slackTeamId: SlackTeamId, slackUserId: SlackUserId, hash: String, turnOn: Boolean) = MaybeUserAction { implicit request =>
    if (SlackTeamMembership.decodeTeamAndUser(hash).safely.contains((slackTeamId, slackUserId))) {
      slackTeamCommander.togglePersonalDigests(slackTeamId, slackUserId, turnOn = turnOn) // this can fail if there is no such membership! (should never happen)
    }
    Redirect(PathCommander.home.absolute)
  }

  private def weWantThisUserToAuthWithSlack(userIdOpt: Option[Id[User]], org: Organization, slackTeamId: SlackTeamId)(implicit session: RSession): Boolean = {
    userIdOpt match {
      case None => true // always hand non-users over to the frontend to ask them to log in or sign up
      case Some(userId) => // if they're logged in AND they can't access the page in question AND signing up with slack would help
        slackTeamRepo.getBySlackTeamId(slackTeamId).exists { slackTeam =>
          val orgIsConnectedToThisSlackTeam = slackTeam.organizationId.contains(org.id.get)
          val userIsNotInThisOrg = orgMembershipRepo.getByOrgIdAndUserId(org.id.get, userId).isEmpty
          val userHasNotGivenUsTheirSlackInfo = !slackTeamMembershipRepo.getByUserId(userId).exists(_.slackTeamId == slackTeamId)
          orgIsConnectedToThisSlackTeam && (userIsNotInThisOrg && userHasNotGivenUsTheirSlackInfo)
        }
    }
  }

  private def redirectThroughSlackAuth(org: Organization, slackTeamId: SlackTeamId, url: String, keepId: Option[PublicId[Keep]] = None, libraryId: Option[PublicId[Library]] = None, userId: Option[ExternalId[User]] = None)(implicit request: MaybeUserRequest[_]): Result = {
    val modelParams = (keepId, libraryId, userId) match {
      case (Some(keepId), _, _) => s"=keep&keepId=${keepId.id}"
      case (_, Some(libraryId), _) => s"=library&libraryId=${libraryId.id}"
      case (_, _, Some(userId)) => s"=welcome&userId=${userId.id}"
      case _ => "=true"
    }

    val slackAuthPage = pathCommander.orgPage(org) + s"?signUpWithSlack" + modelParams + s"&slackTeamId=${slackTeamId.value}"

    Redirect(slackAuthPage.absolute).withSession(request.session + (SecureSocial.OriginalUrlKey -> url)).withCookies(Cookie(PostRegIntent.onFailUrlKey, slackAuthPage.absolute))
  }

  private def notFound(request: MaybeUserRequest[_]): Result = {
    NotFound(views.html.error.notFound(request.path))
  }
}

