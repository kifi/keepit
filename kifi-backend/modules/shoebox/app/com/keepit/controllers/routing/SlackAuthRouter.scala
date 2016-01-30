package com.keepit.controllers.routing

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.http._
import com.keepit.controllers.website.{ DeepLinkRouter, DeepLinkRedirect }
import com.keepit.model._
import com.keepit.slack.models.{ SlackTeamId, SlackTeamMembershipRepo, SlackTeamRepo }
import play.api.mvc.Result
import securesocial.core.SecureSocial
import views.html

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
  val userActionsHelper: UserActionsHelper,
  implicit val defaultContext: ExecutionContext,
  implicit val publicIdConfiguration: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  def fromSlackToInstallPage(slackTeamId: SlackTeamId) = MaybeUserAction { implicit request =>
    val redir = db.readOnlyMaster { implicit s =>
      slackTeamRepo.getBySlackTeamId(slackTeamId).flatMap(_.organizationId).map(orgRepo.get).filter(_.isActive).map { org =>
        val target = PathCommander.browserExtension.absolute
        weWantThisUserToAuthWithSlack(request.userIdOpt, org, slackTeamId) match {
          case true => redirectThroughSlackAuth(org, slackTeamId, None, target)
          case false => Redirect(target)
        }
      }
    }
    redir.getOrElse(notFound(request))
  }
  def fromSlackToUser(slackTeamId: SlackTeamId, extId: ExternalId[User]) = MaybeUserAction { request =>
    // There is currently no authentication needed to see a user's profile
    val redir = db.readOnlyMaster { implicit s =>
      userRepo.getOpt(extId).map { user =>
        Redirect(pathCommander.profilePage(user).absolute)
      }
    }
    redir.getOrElse(notFound(request))
  }
  def fromSlackToOrg(slackTeamId: SlackTeamId, pubId: PublicId[Organization]) = MaybeUserAction { implicit request =>
    val redir = db.readOnlyMaster { implicit s =>
      Organization.decodePublicId(pubId).toOption.flatMap(orgId => Some(orgRepo.get(orgId)).filter(_.isActive)).map { org =>
        val target = pathCommander.orgPage(org).absolute
        weWantThisUserToAuthWithSlack(request.userIdOpt, org, slackTeamId) match {
          case true => redirectThroughSlackAuth(org, slackTeamId, keepIdOpt = None, target)
          case false => Redirect(target)
        }
      }
    }
    redir.getOrElse(notFound(request))
  }
  def fromSlackToOrgIntegrations(slackTeamId: SlackTeamId, pubId: PublicId[Organization]) = MaybeUserAction { implicit request =>
    val redir = db.readOnlyMaster { implicit s =>
      Organization.decodePublicId(pubId).toOption.flatMap(orgId => Some(orgRepo.get(orgId)).filter(_.isActive)).map { org =>
        val target = pathCommander.orgIntegrationsPage(org).absolute
        weWantThisUserToAuthWithSlack(request.userIdOpt, org, slackTeamId) match {
          case true => redirectThroughSlackAuth(org, slackTeamId, None, target)
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
        } yield redirectThroughSlackAuth(org, slackTeamId, keepIdOpt = None, target)) getOrElse Redirect(target)
      }
    }
    redir.getOrElse(notFound(request))
  }

  def fromSlackToKeep(slackTeamId: SlackTeamId, pubId: PublicId[Keep], urlHash: UrlHash, onKifi: Boolean) = MaybeUserAction { implicit request =>
    val (deepLinkRedirect, isLoggedIn) = request match {
      case ur: UserRequest[_] => (ur.kifiInstallationId.isDefined && ur.userAgentOpt.exists(_.canRunExtensionIfUpToDate), true)
      case _ => (false, false)
    }

    // show 3rd party url with ext if possible, otherwise go to keep page (with proper upsells) or 3rd party url
    val redirOpt = db.readOnlyMaster { implicit s =>
      Keep.decodePublicId(pubId)
        .map(keepId => keepRepo.get(keepId)).filter(_.isActive)
        .map { keep =>
          val keepPageUrl = pathCommander.pathForKeep(keep).absolute
          if (deepLinkRedirect) {
            val deepRedirect = DeepLinkRedirect(url = keep.url, externalLocator = Some(s"/messages/${pubId.id}")) // open ext
            Ok(html.mobile.deepLinkRedirect(deepRedirect, DeepLinkRouter.keepLink(pubId, None)))
          } else if (onKifi && isLoggedIn) {
            Redirect(keepPageUrl)
          } else if (onKifi && !isLoggedIn) {
            val orgIdOpt = keep.organizationId.orElse(slackTeamRepo.getBySlackTeamId(slackTeamId).flatMap(_.organizationId))
            val orgOpt = orgIdOpt.map(orgId => orgRepo.get(orgId))
            orgOpt
              .map(org => redirectThroughSlackAuth(org, slackTeamId, Some(pubId), keepPageUrl))
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

  private def redirectThroughSlackAuth(org: Organization, slackTeamId: SlackTeamId, keepIdOpt: Option[PublicId[Keep]], url: String)(implicit request: MaybeUserRequest[_]): Result = {
    val slackAuthPage = pathCommander.orgPage(org) + s"?signUpWithSlack${keepIdOpt.map(_ => s"=keep").getOrElse("")}&slackTeamId=${slackTeamId.value}" + keepIdOpt.map(id => s"&keepId=$id").getOrElse("")
    Redirect(slackAuthPage.absolute).withSession(request.session + (SecureSocial.OriginalUrlKey -> url))
  }

  private def notFound(request: MaybeUserRequest[_]): Result = {
    NotFound(views.html.error.notFound(request.path))
  }
}

