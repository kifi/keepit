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

case class SocialId(id: String)

case class SocialUserInfo(
  id: Option[Id[SocialUserInfo]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Option[Id[User]] = None,
  fullName: String,
  state: State[SocialUserInfo] = SocialUserInfo.States.ACTIVE,
  socialId: SocialId,
  networkType: SocialNetworkType
) {
  
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
    
  def getOpt(id: Id[SocialUserInfo])(implicit conn: Connection): Option[SocialUserInfo] =
    SocialUserInfoEntity.get(id).map(_.view)
    
  object States {
    val ACTIVE = State[SocialUserInfo]("active")
    val INACTIVE = State[SocialUserInfo]("inactive")
  }
} 

private[model] class SocialUserInfoEntity extends Entity[SocialUserInfo, SocialUserInfoEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val userId = "user_id".ID[User]
  val fullName = "full_name".VARCHAR(512).NOT_NULL
  val state = "state".STATE[SocialUserInfo].NOT_NULL(SocialUserInfo.States.ACTIVE)
  val socialId = "social_id".VARCHAR(32).NOT_NULL
  val networkType = "network_type".VARCHAR(32).NOT_NULL
  
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
    }
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
    user
  }
}


