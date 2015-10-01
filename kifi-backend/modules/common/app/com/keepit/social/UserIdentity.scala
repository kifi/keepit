package com.keepit.social

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.common.mail.EmailAddress
import com.keepit.model.view.UserSessionView
import com.keepit.model.{ SocialUserInfo, UserCred, User }
import play.api.libs.functional.syntax._
import play.api.libs.json._

import securesocial.core.{ IdentityId, PasswordInfo, AuthenticationMethod, SocialUser }

import scala.concurrent.duration.Duration
import scala.util.{ Failure, Try }

sealed abstract class MaybeUserIdentity(val userId: Option[Id[User]], val socialUser: SocialUser) extends SocialUser(
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

// todo(Léo): can we make userId not optional in this case?
class UserIdentity(userId: Option[Id[User]], socialUser: SocialUser) extends MaybeUserIdentity(userId, socialUser)

object UserIdentity {
  def apply(userId: Option[Id[User]], socialUser: SocialUser) = new UserIdentity(userId, socialUser)
  def unapply(u: UserIdentity) = Some(u.userId, u.socialUser)

  def apply(user: User, emailAddress: EmailAddress, cred: Option[UserCred]): UserIdentity = {
    val passwordInfo = cred.map(actualCred => PasswordInfo(hasher = "bcrypt", password = actualCred.credentials))
    val socialUser = SocialUser(
      identityId = SocialUserHelpers.toIdentityId(emailAddress),
      firstName = user.firstName,
      lastName = user.lastName,
      fullName = user.fullName,
      email = Some(emailAddress.address),
      avatarUrl = None,
      authMethod = AuthenticationMethod.UserPassword,
      passwordInfo = passwordInfo
    )
    UserIdentity(user.id, socialUser)
  }

  import com.keepit.serializer.SocialUserSerializer._
  implicit val format = (
    (__ \ 'userId).formatNullable(Id.format[User]) and
    (__ \ 'socialUser).format[SocialUser]
  )(UserIdentity.apply, unlift(UserIdentity.unapply))
}

case class UserIdentityIdentityIdKey(id: IdentityId) extends Key[UserIdentity] {
  override val version = 1
  val namespace = "user_identity_by_identity_id"
  def toKey(): String = id.providerId + "_" + id.userId
}

object UserIdentityIdentityIdKey {
  def apply(networkType: SocialNetworkType, socialId: SocialId): UserIdentityIdentityIdKey = UserIdentityIdentityIdKey(SocialUserHelpers.toIdentityId(networkType, socialId))
  def apply(emailAddress: EmailAddress): UserIdentityIdentityIdKey = UserIdentityIdentityIdKey(SocialUserHelpers.toIdentityId(emailAddress))
}

class UserIdentityCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserIdentityIdentityIdKey, UserIdentity](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

class NewUserIdentity(userId: Option[Id[User]], socialUser: SocialUser) extends MaybeUserIdentity(userId, socialUser)

object NewUserIdentity {
  def apply(userId: Option[Id[User]], socialUser: SocialUser) = new NewUserIdentity(userId, socialUser)
  def unapply(u: NewUserIdentity) = Some(u.userId, u.socialUser)
}

object SocialUserHelpers {
  def parseNetworkType(identityId: IdentityId): SocialNetworkType = SocialNetworkType(identityId.providerId)
  def parseNetworkType(socialUser: SocialUser): SocialNetworkType = parseNetworkType(socialUser.identityId)

  def parseSocialId(identityId: IdentityId): SocialId = identityId.userId.trim match {
    case socialId if socialId.nonEmpty => SocialId(socialId)
    case _ => throw new IllegalArgumentException(s"Invalid social id from IdentityId: $identityId")
  }
  def parseSocialId(socialUser: SocialUser): SocialId = parseSocialId(socialUser.identityId)

  def parseEmailAddress(socialUser: SocialUser): Try[EmailAddress] = socialUser.email match {
    case None => Failure(new IllegalArgumentException(s"Email address not fount in SocialUser: $socialUser"))
    case Some(address) => EmailAddress.validate(address)
  }

  def toIdentityId(networkType: SocialNetworkType, socialId: SocialId): IdentityId = IdentityId(userId = socialId.id, providerId = networkType.authProvider)
  def toIdentityId(emailAddress: EmailAddress): IdentityId = IdentityId(userId = emailAddress.address, providerId = SocialNetworks.EMAIL.authProvider)
  def getIdentityId(session: UserSessionView): IdentityId = IdentityId(session.socialId.id, session.provider.name)
}
