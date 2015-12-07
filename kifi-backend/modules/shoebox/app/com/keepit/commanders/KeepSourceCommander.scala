package com.keepit.commanders

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.social.BasicUserRepo
import com.keepit.model._
import com.keepit.slack.models.{ SlackUserId, SlackTeamMembershipRepo }
import com.keepit.social.{ BasicUser, SocialNetworks, SocialId }
import com.keepit.social.twitter.TwitterUserId

@ImplementedBy(classOf[KeepSourceCommanderImpl])
trait KeepSourceCommander {
  def getSourceAttributionForKeeps(keepIds: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], SourceAttribution]
  def getSourceAttributionWithUserIdForKeeps(keepIds: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], (SourceAttribution, Option[Id[User]])]
  def getSourceAttributionWithBasicUserForKeeps(keepIds: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], (SourceAttribution, Option[BasicUser])]
}

@Singleton
class KeepSourceCommanderImpl @Inject() (
    sourceAttributionRepo: KeepSourceAttributionRepo,
    slackTeamMembershipRepo: SlackTeamMembershipRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    basicUserRepo: BasicUserRepo) extends KeepSourceCommander {

  def getSourceAttributionForKeeps(keepIds: Set[Id[Keep]])(implicit session: RSession) = {
    sourceAttributionRepo.getByKeepIds(keepIds)
  }

  def getSourceAttributionWithUserIdForKeeps(keepIds: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], (SourceAttribution, Option[Id[User]])] = {
    val sourcesByKeepIds = getSourceAttributionForKeeps(keepIds)
    val twitterAccountOwners = getTwitterAccountOwners(sourcesByKeepIds.values.collect { case TwitterAttribution(tweet) => tweet.user.id } toSet)
    val slackAccountOwners = getSlackAccountOwners(sourcesByKeepIds.values.collect { case SlackAttribution(message) => message.userId } toSet)
    sourcesByKeepIds.mapValues {
      case attribution @ TwitterAttribution(tweet) => (attribution, twitterAccountOwners.get(tweet.user.id))
      case attribution @ SlackAttribution(message) => (attribution, slackAccountOwners.get(message.userId))
      case attribution => (attribution, None)
    }
  }

  private def getTwitterAccountOwners(userIds: Set[TwitterUserId])(implicit session: RSession): Map[TwitterUserId, Id[User]] = {
    socialUserInfoRepo.getByNetworkAndSocialIds(SocialNetworks.TWITTER, userIds.map(id => SocialId(id.id.toString))).mapValues(_.userId).collect {
      case (SocialId(id), Some(userId)) => TwitterUserId(id.toLong) -> userId
    }
  }

  private def getSlackAccountOwners(userIds: Set[SlackUserId])(implicit session: RSession): Map[SlackUserId, Id[User]] = {
    slackTeamMembershipRepo.getBySlackUserIds(userIds).mapValues(_.userId)
  }

  def getSourceAttributionWithBasicUserForKeeps(keepIds: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], (SourceAttribution, Option[BasicUser])] = {
    val sources = getSourceAttributionWithUserIdForKeeps(keepIds)
    val users = basicUserRepo.loadAll(sources.values.flatMap(_._2).toSet)
    sources.mapValues { case (source, userIdOpt) => (source, userIdOpt.flatMap(users.get)) }
  }
}
