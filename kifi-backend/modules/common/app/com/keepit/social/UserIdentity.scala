package com.keepit.social

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.common.mail.EmailAddress
import com.keepit.common.oauth._
import com.keepit.model.view.UserSessionView
import com.keepit.model.{ UserCred, User }
import com.keepit.slack.models.{ SlackUserId, SlackTeamId }
import play.api.libs.functional.syntax._
import play.api.libs.json._

import securesocial.core._

import scala.concurrent.duration.Duration
import scala.util.{ Failure, Try }

sealed abstract class SocialUserHolder(socialUser: SocialUser) extends SocialUser(
  socialUser.identityId,
  socialUser.firstName,
  socialUser.lastName,
  socialUser.fullName,
  socialUser.email,
  socialUser.avatarUrl,
  socialUser.authMethod,
  socialUser.oAuth1Info,
  socialUser.oAuth2Info,
  socialUser.passwordInfo
)

class RichSocialUser(val identity: RichIdentity) extends SocialUserHolder(RichIdentity.toSocialUser(identity))
object RichSocialUser {
  def apply(identity: RichIdentity): RichSocialUser = new RichSocialUser(identity)
  def unapply(socialUser: RichSocialUser): Option[RichIdentity] = Some(socialUser.identity)
}

sealed abstract class MaybeUserIdentity(val userId: Option[Id[User]], val socialUser: SocialUser) extends SocialUserHolder(socialUser)

class UserIdentity(userId: Option[Id[User]], socialUser: SocialUser) extends MaybeUserIdentity(userId, socialUser)

object UserIdentity {
  def apply(userId: Option[Id[User]], socialUser: SocialUser): UserIdentity = new UserIdentity(userId, socialUser)
  def unapply(u: UserIdentity): Option[(Option[Id[User]], SocialUser)] = Some(u.userId, u.socialUser)

  def apply(userId: Option[Id[User]], identity: RichIdentity): UserIdentity = UserIdentity(userId, RichSocialUser(identity))

  def apply(user: User, emailAddress: EmailAddress, cred: Option[UserCred]): UserIdentity = {
    val passwordInfo = cred.map(actualCred => PasswordInfo(hasher = "bcrypt", password = actualCred.credentials))
    UserIdentity(Some(user.id.get), EmailPasswordIdentity(user.firstName, user.lastName, emailAddress, passwordInfo))
  }

  import com.keepit.serializer.SocialUserSerializer._
  implicit val format = (
    (__ \ 'userId).formatNullable(Id.format[User]) and
    (__ \ 'socialUser).format[SocialUser]
  )(UserIdentity.apply, unlift(UserIdentity.unapply))
}

class NewUserIdentity(userId: Option[Id[User]], socialUser: SocialUser) extends MaybeUserIdentity(userId, socialUser)

object NewUserIdentity {
  def apply(userId: Option[Id[User]], socialUser: SocialUser): NewUserIdentity = new NewUserIdentity(userId, socialUser)
  def unapply(u: NewUserIdentity): Option[(Option[Id[User]], SocialUser)] = Some(u.userId, u.socialUser)
  def apply(userId: Option[Id[User]], identity: RichIdentity): NewUserIdentity = NewUserIdentity(userId, RichSocialUser(identity))
}

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
  def parseSlackId(identityId: IdentityId): (SlackTeamId, SlackUserId) = identityId.userId.trim.split('|').toSeq.filter(_.nonEmpty) match {
    case Seq(teamIdStr, userIdStr) => (SlackTeamId(teamIdStr), SlackUserId(userIdStr))
    case _ => throw new IllegalArgumentException(s"Invalid Slack credentials from IdentityId: $identityId")
  }

  def getIdentityId(session: UserSessionView): IdentityId = IdentityId(session.socialId.id, session.provider.name)
}

case class IdentityUserIdKey(id: IdentityId) extends Key[Id[User]] {
  override val version = 1
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
