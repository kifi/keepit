package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.cache._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.social.{SocialNetworkType, SocialId}
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.concurrent.duration._

case class UserSession(
  id: Option[Id[UserSession]] = None,
  userId: Option[Id[User]] = None,
  externalId: ExternalId[UserSession],
  socialId: SocialId,
  provider: SocialNetworkType,
  expires: DateTime,
  state: State[UserSession] = UserSessionStates.ACTIVE,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime) extends ModelWithExternalId[UserSession] {
  def withId(id: Id[UserSession]) = copy(id = Some(id))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
  def isValid = state == UserSessionStates.ACTIVE && expires.isAfterNow
  def invalidated = copy(state = UserSessionStates.INACTIVE)
}

object UserSession {
  private implicit val idFormat = Id.format[UserSession]
  private implicit val userIdFormat = Id.format[User]
  private implicit val externalIdFormat = ExternalId.format[UserSession]
  private implicit val stateFormat = State.format[UserSession]

  implicit val userSessionFormat: Format[UserSession] = (
    (__ \ 'id).formatNullable[Id[UserSession]] and
    (__ \ 'userId).formatNullable[Id[User]] and
    (__ \ 'externalId).format[ExternalId[UserSession]] and
    (__ \ 'socialId).format[String].inmap(SocialId.apply, unlift(SocialId.unapply)) and
    (__ \ 'provider).format[String].inmap(SocialNetworkType.apply, unlift(SocialNetworkType.unapply)) and
    (__ \ 'expires).format[DateTime] and
    (__ \ 'state).format[State[UserSession]] and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime]
  )(UserSession.apply, unlift(UserSession.unapply))
}

@ImplementedBy(classOf[UserSessionRepoImpl])
trait UserSessionRepo extends Repo[UserSession] with ExternalIdColumnFunction[UserSession]

case class UserSessionExternalIdKey(externalId: ExternalId[UserSession]) extends Key[UserSession] {
  override val version = 1
  val namespace = "user_session_by_external_id"
  def toKey(): String = externalId.id
}
class UserSessionExternalIdCache @Inject() (val repo: FortyTwoCachePlugin)
    extends FortyTwoCache[UserSessionExternalIdKey, UserSession] {
  val ttl = 24 hours
  def deserialize(obj: Any): UserSession = Json.fromJson[UserSession](Json.parse(obj.asInstanceOf[String])).get
  def serialize(userSession: UserSession) = Json.toJson(userSession)
}

@Singleton
class UserSessionRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    val externalIdCache: UserSessionExternalIdCache
  ) extends DbRepo[UserSession] with UserSessionRepo with ExternalIdColumnDbFunction[UserSession] with Logging {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override lazy val table = new RepoTable[UserSession](db, "user_session") with ExternalIdColumn[UserSession] {
    def userId = column[Option[Id[User]]]("user_id", O.Nullable)
    def socialId = column[SocialId]("social_id", O.NotNull)
    def expires = column[DateTime]("expires", O.NotNull)
    def provider = column[SocialNetworkType]("provider", O.NotNull)
    def * = id.? ~ userId ~ externalId ~ socialId ~ provider ~ expires ~ state ~ createdAt ~ updatedAt <>
        (UserSession.apply _, UserSession.unapply _)
  }

  override def invalidateCache(userSession: UserSession)(implicit session: RSession): UserSession = {
    externalIdCache.set(UserSessionExternalIdKey(userSession.externalId), userSession)
    userSession
  }

  override def getOpt(id: ExternalId[UserSession])(implicit session: RSession): Option[UserSession] = {
    externalIdCache.getOrElseOpt(UserSessionExternalIdKey(id)) {
      (for(f <- externalIdColumn if f.externalId === id) yield f).firstOption
    }
  }

  override def get(id: ExternalId[UserSession])(implicit session: RSession): UserSession = getOpt(id).get

}

object UserSessionStates extends States[UserSession]
