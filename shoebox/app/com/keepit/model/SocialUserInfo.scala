package com.keepit.model

import com.keepit.common.db.{CX, Id, Entity, EntityTable, ExternalId, State}
import com.keepit.common.db.NotFoundException
import com.keepit.common.time._
import com.keepit.common.crypto._
import com.keepit.serializer.SocialUserSerializer
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api._
import ru.circumflex.orm._
import securesocial.core.SocialUser
import play.api.libs.json._
import com.keepit.common.social.SocialNetworkType
import com.keepit.common.social.SocialNetworks
import com.keepit.common.social.SocialUserRawInfo
import com.keepit.common.social.SocialNetworks
import com.keepit.common.social.SocialId

case class SocialUserInfo(
  id: Option[Id[SocialUserInfo]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Option[Id[User]] = None,
  fullName: String,
  state: State[SocialUserInfo] = SocialUserInfo.States.CREATED,
  socialId: SocialId,
  networkType: SocialNetworkType,
  credentials: Option[SocialUser] = None
) {
  
  def withUser(user: User) = copy(userId = Some(user.id.get))//want to make sure the user has an id, fail hard if not!
  def withCredentials(credentials: SocialUser) = copy(credentials = Some(credentials))//want to make sure the user has an id, fail hard if not!
  
  def save(implicit conn: Connection): SocialUserInfo = {
    val entity = SocialUserInfoEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }
}

object SocialUserInfo {

  def all(implicit conn: Connection): Seq[SocialUserInfo] =
    SocialUserInfoEntity.all.map(_.view)
  
  def get(id: Id[SocialUserInfo])(implicit conn: Connection): SocialUserInfo =
    getOpt(id).getOrElse(throw NotFoundException(id))
    
  def get(id: SocialId, networkType: SocialNetworkType)(implicit conn: Connection): SocialUserInfo =
    getOpt(id, networkType).getOrElse(throw new Exception("not found %s:%s".format(id, networkType)))
    
  def getByUser(userId: Id[User])(implicit conn: Connection): Seq[SocialUserInfo] =
    (SocialUserInfoEntity AS "u").map { u => SELECT (u.*) FROM u WHERE (u.userId EQ userId) list }.map(_.view)
    
  def getOpt(id: SocialId, networkType: SocialNetworkType)(implicit conn: Connection): Option[SocialUserInfo] =
    (SocialUserInfoEntity AS "u").map { u => SELECT (u.*) FROM u WHERE ((u.socialId EQ id.id) AND (u.networkType EQ networkType.name)) unique }.map(_.view)
    
  def getOpt(id: Id[SocialUserInfo])(implicit conn: Connection): Option[SocialUserInfo] =
    SocialUserInfoEntity.get(id).map(_.view)
    
  object States {
    val CREATED = State[SocialUserInfo]("created")
    val FETCHED_USING_FRIEND = State[SocialUserInfo]("fetched_using_friend")
    val FETCHED_USING_SELF = State[SocialUserInfo]("fetched_using_self")
    val FETCHE_FAIL = State[SocialUserInfo]("fetch_fail")
    val INACTIVE = State[SocialUserInfo]("inactive")
  }
} 

private[model] class SocialUserInfoEntity extends Entity[SocialUserInfo, SocialUserInfoEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val userId = "user_id".ID[User]
  val fullName = "full_name".VARCHAR(512).NOT_NULL
  val state = "state".STATE[SocialUserInfo].NOT_NULL(SocialUserInfo.States.CREATED)
  val socialId = "social_id".VARCHAR(32).NOT_NULL
  val networkType = "network_type".VARCHAR(32).NOT_NULL
  val credentials = "credentials".VARCHAR(2048)

  def relation = SocialUserInfoEntity
  
  def view(implicit conn: Connection): SocialUserInfo = SocialUserInfo(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    fullName = fullName(),
    state = state(),
    socialId = SocialId(socialId()),
    networkType = networkType() match {
      case SocialNetworks.FACEBOOK.name => SocialNetworks.FACEBOOK
      case _ => throw new RuntimeException("unknown network type %s".format(networkType()))
    },
    credentials = credentials.map{ s => new SocialUserSerializer().reads(Json.parse(s)) }
  )
}

private[model] object SocialUserInfoEntity extends SocialUserInfoEntity with EntityTable[SocialUserInfo, SocialUserInfoEntity] {
  override def relationName = "social_user_info"
  
  def apply(view: SocialUserInfo): SocialUserInfoEntity = {
    val user = new SocialUserInfoEntity
    user.id.set(view.id)
    user.createdAt := view.createdAt
    user.updatedAt := view.updatedAt
    user.userId.set(view.userId)
    user.fullName := view.fullName
    user.state := view.state
    user.socialId := view.socialId.id
    user.networkType := view.networkType.name
    user.credentials.set(view.credentials.map{ s => new SocialUserSerializer().writes(s).toString() })
    user
  }
}


