package com.keepit.social

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.common.mail.EmailAddress
import com.keepit.model.User
import com.keepit.model.view.UserSessionView
import com.keepit.slack.models.{ SlackUserId, SlackTeamId }
import securesocial.core.{ Identity, IdentityId }

import scala.concurrent.duration.Duration
import scala.util.{ Success, Failure, Try }

object IdentityHelpers {
  def parseNetworkType(identityId: IdentityId): SocialNetworkType = SocialNetworkType(identityId.providerId)
  def parseNetworkType(identity: Identity): SocialNetworkType = parseNetworkType(identity.identityId)

  def toIdentityId(networkType: SocialNetworkType, socialId: SocialId): IdentityId = IdentityId(userId = socialId.id, providerId = networkType.authProvider)
  def parseSocialId(identityId: IdentityId): SocialId = identityId.userId.trim match {
    case socialId if socialId.nonEmpty => SocialId(socialId)
    case _ => throw new IllegalArgumentException(s"Invalid social id from IdentityId: $identityId")
  }
  def parseSocialId(identity: Identity): SocialId = parseSocialId(identity.identityId)

  def toIdentityId(emailAddress: EmailAddress): IdentityId = IdentityId(userId = emailAddress.address, providerId = SocialNetworks.EMAIL.authProvider)
  def parseEmailAddress(identity: Identity): Try[EmailAddress] = identity.email match {
    case None => Failure(new IllegalArgumentException(s"Email address not found in $identity"))
    case Some(address) => EmailAddress.validate(address)
  }

  def toIdentityId(teamId: SlackTeamId, userId: SlackUserId): IdentityId = IdentityId(userId = s"${teamId.value}|${userId.value}", providerId = SocialNetworks.SLACK.authProvider)
  def parseSlackId(identityId: IdentityId): (SlackTeamId, SlackUserId) = parseSlackIdMaybe(identityId).get
  def parseSlackIdMaybe(identityId: IdentityId): Try[(SlackTeamId, SlackUserId)] = identityId.userId.trim.split('|').toSeq.filter(_.nonEmpty) match {
    case Seq(teamIdStr, userIdStr) => Success((SlackTeamId(teamIdStr), SlackUserId(userIdStr)))
    case _ => Failure(new IllegalArgumentException(s"Invalid Slack credentials from IdentityId: $identityId"))
  }

  def getIdentityId(session: UserSessionView): IdentityId = IdentityId(session.socialId.id, session.provider.name)
}

case class IdentityUserIdKey(id: IdentityId) extends Key[Id[User]] {
  override val version = 3
  val namespace = "user_id_by_identity_id"
  def toKey(): String = id.providerId + "_" + id.userId
}

object IdentityUserIdKey {
  def apply(networkType: SocialNetworkType, socialId: SocialId): IdentityUserIdKey = IdentityUserIdKey(IdentityHelpers.toIdentityId(networkType, socialId))
  def apply(emailAddress: EmailAddress): IdentityUserIdKey = IdentityUserIdKey(IdentityHelpers.toIdentityId(emailAddress))
  def apply(teamId: SlackTeamId, userId: SlackUserId): IdentityUserIdKey = IdentityUserIdKey(IdentityHelpers.toIdentityId(teamId, userId))
}

class IdentityUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[IdentityUserIdKey, Id[User]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
