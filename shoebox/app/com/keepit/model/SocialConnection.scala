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
import com.keepit.common.logging.Logging

case class SocialConnection(
  id: Option[Id[SocialConnection]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  socialUser1: Id[SocialUserInfo],
  socialUser2: Id[SocialUserInfo],
  state: State[SocialConnection] = SocialConnection.States.ACTIVE
) {
  
  def save(implicit conn: Connection): SocialConnection = {
    val entity = SocialConnectionEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }
  
}

object SocialConnection extends Logging {

  def all(implicit conn: Connection): Seq[SocialConnection] =
    SocialConnectionEntity.all.map(_.view)
  
  def get(id: Id[SocialConnection])(implicit conn: Connection): SocialConnection =
    getOpt(id).getOrElse(throw NotFoundException(id))
    
  def getOpt(id: Id[SocialConnection])(implicit conn: Connection): Option[SocialConnection] =
    SocialConnectionEntity.get(id).map(_.view)
    
    
  def getFortyTwoUserConnections(id: Id[User])(implicit conn: Connection): Set[Id[User]] = {
    val statement = conn.createStatement
    val suidSQL = """
        select 
             id 
        from 
             social_user_info 
        where 
             user_id = %s""".format(id.id)
    val connectionsSQL = """
        select 
             social_user_1, social_user_2
        from 
             (%s) as suid, social_connection as sc 
        where 
             (sc.social_user_1 in (suid.id)) or (sc.social_user_2 in (suid.id))""".format(suidSQL)
    val rs = statement.executeQuery("""
        select 
             sui.user_id
        from 
             (%s) as connections, 
             social_user_info as sui 
        where 
             (sui.id in (connections.social_user_1) or sui.id in (connections.social_user_2))
             AND
             (sui.user_id is not null)
             AND
             (sui.user_id != %s)""".format(connectionsSQL, id.id))
    try {
      Iterator.continually((rs, rs.next)).takeWhile(_._2).map(_._1).map(res => Id[User](res.getLong("user_id"))).toSet
    }
    finally {
      statement.close
      rs.close
    }
  }
    
  def getConnectionOpt(u1: Id[SocialUserInfo], u2: Id[SocialUserInfo])(implicit conn: Connection): Option[SocialConnection] = {
    (SocialConnectionEntity AS "sc").map { sc => SELECT (sc.*) FROM sc WHERE (
        ((sc.socialUser1 EQ u1) AND (sc.socialUser2 EQ u2)) OR 
        ((sc.socialUser1 EQ u2) AND (sc.socialUser2 EQ u1))) unique } map ( _.view )
  }

  
  def getUserConnectionsCount(id: Id[User])(implicit conn: Connection): Long = {
    val suis = SocialUserInfo.getByUser(id).map(_.id.get)
    (SocialConnectionEntity AS "sc").map { sc =>
      SELECT (COUNT(sc.id)) FROM sc WHERE ((sc.socialUser1 IN (suis)) OR (sc.socialUser2 IN (suis))) unique
    } get
  }
  
  def getUserConnections(id: Id[User])(implicit conn: Connection): Seq[SocialUserInfo] = {
    val suis = SocialUserInfo.getByUser(id).map(_.id.get)
    val conns = (SocialConnectionEntity AS "sc").map { sc =>
      SELECT (sc.*) FROM sc WHERE ((sc.socialUser1 IN (suis)) OR (sc.socialUser2 IN (suis))) list
    } map (_.view) map (s => if(suis.contains(s.socialUser1)) s.socialUser2 else s.socialUser1 ) toList
    
    (SocialUserInfoEntity AS "sui").map { sui =>
      SELECT (sui.*) FROM sui WHERE (sui.id IN(conns)) list
    } map (_.view)
  }
  
  def getSocialUserConnections(id: Id[SocialUserInfo])(implicit conn: Connection): Seq[SocialUserInfo] = {
    
    val conns = (SocialConnectionEntity AS "sc").map { sc =>
      SELECT (sc.*) FROM sc WHERE ((sc.socialUser1 EQ id) OR (sc.socialUser2 EQ id)) list
    } map (_.view) map (s => if(id == s.socialUser1) s.socialUser2 else s.socialUser1 ) toList
    
    (SocialUserInfoEntity AS "sui").map { sui =>
      SELECT (sui.*) FROM sui WHERE (sui.id IN(conns)) list
    } map (_.view)
    
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


