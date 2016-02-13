package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.cache._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.{ AccessLog, Logging }
import com.keepit.common.oauth.SlackIdentity
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.social.Author
import play.api.libs.json._
import com.keepit.common.json.{ TraversableFormat, formatNone }
import com.keepit.common.core._

import scala.concurrent.{ Future, ExecutionContext }
import scala.concurrent.duration.Duration
import scala.util.{ Failure, Success, Try }

object SlackCommander {
  val slackSetupPermission = OrganizationPermission.CREATE_SLACK_INTEGRATION
}

@ImplementedBy(classOf[SlackCommanderImpl])
trait SlackCommander {
  def registerAuthorization(userId: Option[Id[User]], auth: SlackAuthorizationResponse, identity: SlackIdentifyResponse): Option[Id[SlackIncomingWebhookInfo]]
  def internSlackIdentity(userIdOpt: Option[Id[User]], identity: SlackIdentity)(implicit session: RWSession): Boolean
  def getIdentityAndMissingScopes(userId: Id[User], slackTeamIdOpt: Option[SlackTeamId], action: SlackAuthenticatedAction): Future[(Option[(SlackTeamId, SlackUserId)], Set[SlackAuthScope])]
}

@Singleton
class SlackCommanderImpl @Inject() (
  db: Database,
  slackTeamRepo: SlackTeamRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  slackIncomingWebhookInfoRepo: SlackIncomingWebhookInfoRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  orgMembershipCommander: OrganizationMembershipCommander,
  slackClient: SlackClientWrapper,
  keepSourceCommander: KeepSourceCommander,
  implicit val executionContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends SlackCommander with Logging {

  def registerAuthorization(userIdOpt: Option[Id[User]], auth: SlackAuthorizationResponse, identity: SlackIdentifyResponse): Option[Id[SlackIncomingWebhookInfo]] = {
    require(auth.teamId == identity.teamId && auth.teamName == identity.teamName)
    db.readWrite { implicit s =>
      slackTeamRepo.internSlackTeam(auth.teamId, auth.teamName, auth.botAuth)
      internSlackIdentity(userIdOpt, SlackIdentity(auth, identity, None))
      auth.incomingWebhook.map { webhook =>
        slackIncomingWebhookInfoRepo.save(SlackIncomingWebhookInfo(
          slackUserId = identity.userId,
          slackTeamId = identity.teamId,
          slackChannelId = webhook.channelId,
          webhook = webhook,
          lastPostedAt = None
        )).id.get
      }
    }
  }

  def internSlackIdentity(userIdOpt: Option[Id[User]], identity: SlackIdentity)(implicit session: RWSession): Boolean = {
    slackTeamRepo.internSlackTeam(identity.teamId, identity.teamName, botAuth = None)
    val (membership, isNewIdentityOwner) = slackTeamMembershipRepo.internMembership(SlackTeamMembershipInternRequest(
      userId = userIdOpt,
      slackUserId = identity.userId,
      slackUsername = identity.username,
      slackTeamId = identity.teamId,
      slackTeamName = identity.teamName,
      token = identity.token,
      scopes = identity.scopes,
      slackUser = identity.user
    ))
    autojoinOrganization(membership)
    userIdOpt.foreach { userId =>
      session.onTransactionSuccess {
        keepSourceCommander.reattributeKeeps(Author.SlackUser(identity.teamId, identity.userId), userId)
      }
    }
    isNewIdentityOwner
  }

  private def autojoinOrganization(membership: SlackTeamMembership)(implicit session: RWSession): Unit = {
    membership.userId.foreach { userId =>
      slackTeamRepo.getBySlackTeamId(membership.slackTeamId).foreach { team =>
        team.organizationId.foreach { orgId =>
          if (orgMembershipRepo.getByOrgIdAndUserId(orgId, userId).isEmpty) {
            orgMembershipCommander.unsafeAddMembership(OrganizationMembershipAddRequest(orgId, userId, userId))
          }
        }
      }
    }
  }

  def getIdentityAndMissingScopes(userId: Id[User], slackTeamIdOpt: Option[SlackTeamId], action: SlackAuthenticatedAction): Future[(Option[(SlackTeamId, SlackUserId)], Set[SlackAuthScope])] = {
    val savedIdentityAndExistingScopesOpt = for {
      slackTeamId <- slackTeamIdOpt
      slackTeamMembership <- db.readOnlyMaster { implicit session =>
        slackTeamMembershipRepo.getByUserId(userId).find(_.slackTeamId == slackTeamId)
      }
      tokenWithScopes <- slackTeamMembership.tokenWithScopes
    } yield (slackTeamId, slackTeamMembership.slackUserId, tokenWithScopes)

    val futureValidIdentityAndExistingScopes = savedIdentityAndExistingScopesOpt match {
      case Some((slackTeamId, slackUserId, SlackTokenWithScopes(token, existingScopes))) => slackClient.validateToken(token).imap {
        case true => (Some((slackTeamId, slackUserId)), existingScopes)
        case false => (None, Set.empty[SlackAuthScope])
      }
      case None => Future.successful((None, Set.empty[SlackAuthScope]))
    }

    futureValidIdentityAndExistingScopes.imap {
      case (identityOpt, existingScopes) => (identityOpt, action.getMissingScopes(existingScopes) ++ Some(SlackAuthScope.Bot).filter(_ => slackTeamIdOpt.safely.contains(KifiSlackApp.BrewstercorpTeamId)))
    }
  }
}
