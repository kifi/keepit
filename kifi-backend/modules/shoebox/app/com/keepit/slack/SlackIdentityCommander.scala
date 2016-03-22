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

object SlackIdentityCommander {
  val slackSetupPermission = OrganizationPermission.CREATE_SLACK_INTEGRATION
}

@ImplementedBy(classOf[SlackIdentityCommanderImpl])
trait SlackIdentityCommander {
  def registerAuthorization(userId: Option[Id[User]], auth: SlackAuthorizationResponse, identity: SlackIdentifyResponse): Option[Id[SlackIncomingWebhookInfo]]
  def internSlackIdentity(userIdOpt: Option[Id[User]], identity: SlackIdentity)(implicit session: RWSession): Boolean
  def getIdentityAndExistingScopes(userId: Option[Id[User]], slackTeamIdOpt: Option[SlackTeamId]): Future[(Option[(SlackTeamId, SlackUserId)], Set[SlackAuthScope])]
}

@Singleton
class SlackIdentityCommanderImpl @Inject() (
  db: Database,
  slackTeamRepo: SlackTeamRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  slackChannelRepo: SlackChannelRepo,
  slackIncomingWebhookInfoRepo: SlackIncomingWebhookInfoRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  orgMembershipCommander: OrganizationMembershipCommander,
  slackClient: SlackClientWrapper,
  keepSourceCommander: KeepSourceCommander,
  slackChannelCommander: SlackChannelCommander,
  implicit val executionContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends SlackIdentityCommander with Logging {

  def registerAuthorization(userIdOpt: Option[Id[User]], auth: SlackAuthorizationResponse, identity: SlackIdentifyResponse): Option[Id[SlackIncomingWebhookInfo]] = {
    require(auth.teamId == identity.teamId && auth.teamName == identity.teamName)
    db.readWrite { implicit s =>
      slackTeamRepo.internSlackTeam(auth.teamId, auth.teamName, auth.botAuth)
      internSlackIdentity(userIdOpt, SlackIdentity(auth, identity, None))
      auth.incomingWebhook.map { webhook =>
        slackChannelRepo.getOrCreate(identity.teamId, webhook.channelId, webhook.channelName)
        slackIncomingWebhookInfoRepo.save(SlackIncomingWebhookInfo(
          slackUserId = identity.userId,
          slackTeamId = identity.teamId,
          slackChannelId = webhook.channelId,
          url = webhook.url,
          configUrl = webhook.configUrl,
          lastPostedAt = None
        )).id.get
      }
    }
  }

  def internSlackIdentity(userIdOpt: Option[Id[User]], identity: SlackIdentity)(implicit session: RWSession): Boolean = {
    slackTeamRepo.internSlackTeam(identity.teamId, identity.teamName, botAuth = None)

    // Disconnect previous identity (enforce 1 Slack User <=> Kifi User x Slack Team)
    userIdOpt.foreach { userId =>
      slackTeamMembershipRepo.getByUserIdAndSlackTeam(userId, identity.teamId).foreach { existingMembership =>
        if (existingMembership.slackUserId != identity.userId) {
          slackTeamMembershipRepo.save(existingMembership.copy(userId = None))
        }
      }
    }

    // Intern identity
    val (membership, isNewIdentityOwner) = slackTeamMembershipRepo.internMembership(SlackTeamMembershipInternRequest(
      userId = userIdOpt,
      slackUserId = identity.userId,
      slackUsername = identity.username,
      slackTeamId = identity.teamId,
      slackTeamName = identity.teamName,
      tokenWithScopes = identity.tokenWithScopes,
      slackUser = identity.user
    ))
    autojoinOrganization(membership)
    userIdOpt.foreach { userId =>
      if (isNewIdentityOwner) {
        session.onTransactionSuccess {
          slackChannelCommander.syncChannelMemberships(identity.teamId, Some(identity.userId))
          keepSourceCommander.reattributeKeeps(Author.SlackUser(identity.teamId, identity.userId), userId)
        }
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

  def getIdentityAndExistingScopes(userId: Option[Id[User]], slackTeamIdOpt: Option[SlackTeamId]): Future[(Option[(SlackTeamId, SlackUserId)], Set[SlackAuthScope])] = {
    val futureInheritableBotScopes = getInheritableBotScopes(slackTeamIdOpt)
    val futureValidIdentityAndExistingUserScopes = getValidIdentityAndExistingUserScopes(userId, slackTeamIdOpt)
    for {
      (identityOpt, existingUserScopes) <- futureValidIdentityAndExistingUserScopes
      inheritableBotScopes <- futureInheritableBotScopes
    } yield (identityOpt, existingUserScopes ++ inheritableBotScopes)
  }

  private def getInheritableBotScopes(slackTeamIdOpt: Option[SlackTeamId]): Future[Set[SlackAuthScope]] = {
    val kifiBotTokenOpt = for {
      slackTeamId <- slackTeamIdOpt
      slackTeam <- db.readOnlyMaster { implicit session =>
        slackTeamRepo.getBySlackTeamId(slackTeamId)
      }
      kifiBotToken <- slackTeam.kifiBot.map(_.token)
    } yield kifiBotToken

    kifiBotTokenOpt match {
      case Some(kifiBotToken) => slackClient.validateToken(kifiBotToken).imap {
        case true => SlackAuthScope.inheritableBotScopes
        case false => Set.empty[SlackAuthScope]
      }
      case None => Future.successful(Set.empty[SlackAuthScope])
    }
  }

  private def getValidIdentityAndExistingUserScopes(userIdOpt: Option[Id[User]], slackTeamIdOpt: Option[SlackTeamId]): Future[(Option[(SlackTeamId, SlackUserId)], Set[SlackAuthScope])] = {
    val savedIdentityAndExistingUserScopesOpt = for {
      userId <- userIdOpt
      slackTeamId <- slackTeamIdOpt
      slackTeamMembership <- db.readOnlyMaster { implicit session =>
        slackTeamMembershipRepo.getByUserIdAndSlackTeam(userId, slackTeamId)
      }
      tokenWithScopes <- slackTeamMembership.tokenWithScopes
    } yield (slackTeamId, slackTeamMembership.slackUserId, tokenWithScopes)

    savedIdentityAndExistingUserScopesOpt match {
      case Some((slackTeamId, slackUserId, SlackTokenWithScopes(userToken, existingUserScopes))) => slackClient.validateToken(userToken).imap {
        case true => (Some((slackTeamId, slackUserId)), existingUserScopes -- SlackAuthScope.ignoredUserScopes)
        case false => (None, Set.empty[SlackAuthScope])
      }
      case None => Future.successful((None, Set.empty[SlackAuthScope]))
    }
  }
}
