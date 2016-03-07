package com.keepit.commanders

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.core.mapExtensionOps
import com.keepit.common.core._
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.social.{ Author, BasicUser, SocialNetworks, SocialId }
import com.keepit.social.twitter.TwitterUserId

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[KeepSourceCommanderImpl])
trait KeepSourceCommander {
  // TODO(ryan): once we have made `Keep.userId` optional and have set up proper attribution backfilling, remove the Option[BasicUser] here
  // it is trying to hotfix misattributed keeps, and we should instead just do the Right Thing the first time
  def getSourceAttributionForKeeps(keepIds: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], (SourceAttribution, Option[BasicUser])]

  def reattributeKeeps(author: Author, user: Id[User], overwriteExistingOwner: Boolean = false): Set[Id[Keep]]
}

@Singleton
class KeepSourceCommanderImpl @Inject() (
  db: Database,
  sourceAttributionRepo: KeepSourceAttributionRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  basicUserRepo: BasicUserRepo,
  keepRepo: KeepRepo,
  keepCommander: KeepCommander,
  implicit val defaultContext: ExecutionContext)
    extends KeepSourceCommander with Logging {

  // Get the source attribution for the provided keeps
  // then look at the a couple of tables to see if any of those attributions can be re-assigned to an actual Kifi user
  // if they have, provide that basic user as well
  def getSourceAttributionForKeeps(keepIds: Set[Id[Keep]])(implicit session: RSession) = {
    val sourcesByKeepId = sourceAttributionRepo.getByKeepIds(keepIds)
    val attributions = sourcesByKeepId.values.toSeq

    val userByTwitterId = {
      val twitterUserIds = attributions.collect { case TwitterAttribution(tweet) => tweet.user.id }.toSet
      getTwitterAccountOwners(twitterUserIds)
    }
    val userBySlackIdentity = {
      val slackIdentities = attributions.collect { case SlackAttribution(message, teamId) => (teamId, message.userId) }.toSet
      getSlackAccountOwners(slackIdentities)
    }
    val userIds = (userByTwitterId.values ++ userBySlackIdentity.values).toSet
    val basicUserById = basicUserRepo.loadAll(userIds)
    sourcesByKeepId.mapValuesStrict { attr =>
      val userIdOpt = attr match {
        case TwitterAttribution(tweet) => userByTwitterId.get(tweet.user.id)
        case SlackAttribution(message, teamId) => userBySlackIdentity.get((teamId, message.userId))
      }
      (attr, userIdOpt.flatMap(basicUserById.get))
    }
  }

  private def getTwitterAccountOwners(userIds: Set[TwitterUserId])(implicit session: RSession): Map[TwitterUserId, Id[User]] = {
    socialUserInfoRepo.getByNetworkAndSocialIds(SocialNetworks.TWITTER, userIds.map(id => SocialId(id.id.toString))).mapValues(_.userId).collect {
      case (SocialId(id), Some(userId)) => TwitterUserId(id.toLong) -> userId
    }
  }

  private def getSlackAccountOwners(identities: Set[(SlackTeamId, SlackUserId)])(implicit session: RSession): Map[(SlackTeamId, SlackUserId), Id[User]] = {
    slackTeamMembershipRepo.getBySlackIdentities(identities).flatMap { case (identity, membership) => membership.userId.map(identity -> _) }
  }

  def reattributeKeeps(author: Author, userId: Id[User], overwriteExistingOwner: Boolean = false): Set[Id[Keep]] = {
    val keepIds = db.readOnlyMaster { implicit session => sourceAttributionRepo.getKeepIdsByAuthor(author) }
    keepIds.grouped(100).flatMap { batchedKeepIds =>
      db.readWrite { implicit s =>
        keepRepo.getByIds(batchedKeepIds).values.collect {
          case keep if !keep.userId.contains(userId) && (keep.userId.isEmpty || overwriteExistingOwner) => keepCommander.setKeepOwner(keep, userId).id.get
        }
      }
    }.toSet
  }
}

@Singleton
class KeepSourceAugmentor @Inject() (
    slackTeamMembershipRepo: SlackTeamMembershipRepo,
    slackChannelRepo: SlackChannelRepo) extends Logging {

  def rawToSourceAttribution(source: RawSourceAttribution)(implicit session: RSession): SourceAttribution = source match {
    case RawTwitterAttribution(tweet) => TwitterAttribution(BasicTweet.fromRawTweet(tweet))
    case RawSlackAttribution(message, teamId) => SlackAttribution(genBasicSlackMessage(teamId, message), teamId)
  }

  private val slackIdentifier = """<[@#][A-Z].*?>""".r
  private def genBasicSlackMessage(teamId: SlackTeamId, message: SlackMessage)(implicit session: RSession) = {
    val improvedText = slackIdentifier.findMatchesAndInterstitials(message.text).map {
      case Right(identifier) =>
        val whole = identifier.group(1)
        val id = whole.substring(2, whole.length - 1)
        id.headOption.collect {
          case 'U' => slackTeamMembershipRepo.getBySlackTeamAndUser(teamId, SlackUserId(id)).map(s => "<@" + s.slackUsername.value + ">")
          case 'C' => slackChannelRepo.getByChannelId(teamId, SlackChannelId(id)).map(s => "<#" + s.slackChannelName.value + ">")
        }.getOrElse {
          log.warn(s"[genBasicSlackMessage] Unknown Slack id $whole")
          ""
        }
      case Left(literal) => literal
    }.mkString("")
    BasicSlackMessage(SlackChannelIdAndPrettyName.from(message.channel), message.userId, message.username, message.timestamp, message.permalink, improvedText)
  }
}
