package com.keepit.controllers.routing

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.controllers.website.SlackController
import com.keepit.model._
import com.keepit.slack.models.{ SlackTeamRepo, SlackTeamMembershipRepo, SlackTeamId }
import play.api.mvc.Result
import securesocial.core.SecureSocial

import scala.concurrent.ExecutionContext

@Singleton
class SlackAuthRouter @Inject() (
  db: Database,
  userRepo: UserRepo,
  orgRepo: OrganizationRepo,
  slackTeamRepo: SlackTeamRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  libraryRepo: LibraryRepo,
  permissionCommander: PermissionCommander,
  pathCommander: PathCommander,
  val userActionsHelper: UserActionsHelper,
  implicit val defaultContext: ExecutionContext,
  implicit val publicIdConfiguration: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

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
        } yield redirectThroughSlackAuth(org, slackTeamId, target)) getOrElse Redirect(target)
      }
    }
    redir.getOrElse(notFound(request))
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

  private def redirectThroughSlackAuth(org: Organization, slackTeamId: SlackTeamId, url: String)(implicit request: MaybeUserRequest[_]): Result = {
    val slackAuthPage = pathCommander.orgPage(org) + s"?signUpWithSlack&slackTeamId=${slackTeamId.value}"
    Redirect(slackAuthPage.absolute).withSession(request.session + (SecureSocial.OriginalUrlKey -> url))
  }

  private def notFound(request: MaybeUserRequest[_]): Result = {
    NotFound(views.html.error.notFound(request.path))
  }
}

