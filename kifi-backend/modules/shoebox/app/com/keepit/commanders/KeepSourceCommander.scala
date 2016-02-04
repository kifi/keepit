package com.keepit.commanders

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.core.mapExtensionOps
import com.keepit.model._
import com.keepit.slack.models.{ SlackUserId, SlackTeamMembershipRepo }
import com.keepit.social.{ BasicUser, SocialNetworks, SocialId }
import com.keepit.social.twitter.TwitterUserId

@ImplementedBy(classOf[KeepSourceCommanderImpl])
trait KeepSourceCommander {
  // TODO(ryan): once we have made `Keep.userId` optional and have set up proper attribution backfilling, remove the Option[BasicUser] here
  // it is trying to hotfix misattributed keeps, and we should instead just do the Right Thing the first time
  def getSourceAttributionForKeeps(keepIds: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], (SourceAttribution, Option[BasicUser])]
}

@Singleton
class KeepSourceCommanderImpl @Inject() (
  sourceAttributionRepo: KeepSourceAttributionRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  basicUserRepo: BasicUserRepo)
    extends KeepSourceCommander {

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
    val userBySlackId = {
      val slackUserIds = attributions.collect { case SlackAttribution(message) => message.user.id }.toSet
      getSlackAccountOwners(slackUserIds)
    }
    val userIds = (userByTwitterId.values ++ userBySlackId.values).toSet
    val basicUserById = basicUserRepo.loadAll(userIds)
    sourcesByKeepId.mapValuesStrict { attr =>
      val userIdOpt = attr match {
        case TwitterAttribution(tweet) => userByTwitterId.get(tweet.user.id)
        case SlackAttribution(message) => userBySlackId.get(message.user.id)
      }
      (attr, userIdOpt.flatMap(basicUserById.get))
    }
  }

  private def getTwitterAccountOwners(userIds: Set[TwitterUserId])(implicit session: RSession): Map[TwitterUserId, Id[User]] = {
    socialUserInfoRepo.getByNetworkAndSocialIds(SocialNetworks.TWITTER, userIds.map(id => SocialId(id.id.toString))).mapValues(_.userId).collect {
      case (SocialId(id), Some(userId)) => TwitterUserId(id.toLong) -> userId
    }
  }

  private def getSlackAccountOwners(userIds: Set[SlackUserId])(implicit session: RSession): Map[SlackUserId, Id[User]] = {
    slackTeamMembershipRepo.getBySlackUserIds(userIds).flatMap { case (slackUserId, membership) => membership.userId.map(slackUserId -> _) }
  }
}
