package com.keepit.model

import com.keepit.common.db.{CX, Id, Entity, EntityTable, ExternalId, State}
import com.keepit.common.db.NotFoundException
import com.keepit.common.time._
import com.keepit.common.crypto._
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api._
import ru.circumflex.orm._
import play.api.libs.json._

case class SocialConnection(
  id: Option[Id[SocialConnection]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  socialUser1: Id[SocialUserInfo],
  socialUser2: Id[SocialUserInfo],
  state: State[SocialConnection] = SocialConnection.States.ACTIVE
) {
  //def withName(firstName: String, lastName: String) = copy(firstName = firstName, lastName = lastName)
  
  def save(implicit conn: Connection): SocialConnection = {
    val entity = SocialConnectionEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }
  
}

object SocialConnection {
  //Used for admin, checking that we can talk with the db
  def loadTest()(implicit conn: Connection): Unit = {
    val socialconnection: Option[SocialConnectionEntity] = (SocialConnectionEntity AS "sc").map { sc =>
      SELECT (sc.*) FROM sc LIMIT 1
    } unique;
    socialconnection.get.view
  }

  def all(implicit conn: Connection): Seq[SocialConnection] =
    SocialConnectionEntity.all.map(_.view)
  
  def get(id: Id[SocialConnection])(implicit conn: Connection): SocialConnection =
    getOpt(id).getOrElse(throw NotFoundException(id))
    
  def getOpt(id: Id[SocialConnection])(implicit conn: Connection): Option[SocialConnection] =
    SocialConnectionEntity.get(id).map(_.view)
    
  /*def getByUser(id: Id[User]) = // bi-directional
    (SocialConnectionEntity AS "sc").map { sc => 
      (SELECT ) list 
    }.map(_.view)*/
    
  def getByUser(id: Id[User])(implicit conn: Connection) = {
    val rs = conn.createStatement.executeQuery("select sui.user_id from (select social_user_1, social_user_2 from (select id from social_user_info where user_id = " + id.id + ") as suid, social_connection as sc where (sc.social_user_1 in (suid.id)) or (sc.social_user_2 in (suid.id))) as connections, social_user_info as sui where sui.id in (connections.social_user_1) or sui.id in (connections.social_user_2)")
    val result = Iterator.continually((rs, rs.next)).takeWhile(_._2).map(_._1).map(res => Id[User](res.getLong("user_id"))).toSet
    result.filterNot(_ == id)

  }

    
  object States {
    val ACTIVE = State[SocialConnection]("active")
    val INACTIVE = State[SocialConnection]("inactive")
  }
} 

private[model] class SocialConnectionEntity extends Entity[SocialConnection, SocialConnectionEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val socialUser1 = "social_user_1".ID[SocialUserInfo].NOT_NULL
  val socialUser2 = "social_user_2".ID[SocialUserInfo].NOT_NULL
  val state = "state".STATE[SocialConnection].NOT_NULL(SocialConnection.States.ACTIVE)
  
  def relation = SocialConnectionEntity
  
  def view(implicit conn: Connection): SocialConnection = SocialConnection(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    socialUser1 = socialUser1(),
    socialUser2 = socialUser2(),
    state = state()
  )
}

private[model] object SocialConnectionEntity extends SocialConnectionEntity with EntityTable[SocialConnection, SocialConnectionEntity] {
  override def relationName = "social_connection"
  
  def apply(view: SocialConnection): SocialConnectionEntity = {
    val socialconnection = new SocialConnectionEntity
    socialconnection.id.set(view.id)
    socialconnection.createdAt := view.createdAt
    socialconnection.updatedAt := view.updatedAt
    socialconnection.socialUser1 := view.socialUser1
    socialconnection.socialUser2 := view.socialUser2
    socialconnection.state := view.state
    socialconnection
  }
}


