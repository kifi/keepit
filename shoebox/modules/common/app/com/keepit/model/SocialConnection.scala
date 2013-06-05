package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import com.keepit.common.crypto._
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api._
import play.api.libs.json._

case class SocialConnection(
  id: Option[Id[SocialConnection]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  socialUser1: Id[SocialUserInfo],
  socialUser2: Id[SocialUserInfo],
  state: State[SocialConnection] = SocialConnectionStates.ACTIVE
) extends Model[SocialConnection] {
  def withId(id: Id[SocialConnection]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[SocialConnection]) = copy(state = state)
}

@ImplementedBy(classOf[SocialConnectionRepoImpl])
trait SocialConnectionRepo extends Repo[SocialConnection] {
  def getFortyTwoUserConnections(id: Id[User])(implicit session: RSession): Set[Id[User]]
  def getConnectionOpt(u1: Id[SocialUserInfo], u2: Id[SocialUserInfo] )(implicit session: RSession): Option[SocialConnection]
  def getUserConnections(id: Id[User])(implicit session: RSession): Seq[SocialUserInfo]
  def getSocialUserConnections(id: Id[SocialUserInfo])(implicit session: RSession): Seq[SocialUserInfo]
}

@Singleton
class SocialConnectionRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    socialRepo: SocialUserInfoRepoImpl)
  extends DbRepo[SocialConnection] with SocialConnectionRepo {

  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import scala.slick.jdbc.StaticQuery
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[SocialConnection](db, "social_connection") {
    def socialUser1 = column[Id[SocialUserInfo]]("social_user_1", O.NotNull)
    def socialUser2 = column[Id[SocialUserInfo]]("social_user_2", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ socialUser1 ~ socialUser2 ~ state <> (SocialConnection, SocialConnection.unapply _)
  }

  def getFortyTwoUserConnections(id: Id[User])(implicit session: RSession): Set[Id[User]] = {
    val suidSQL = """
        select
             id
        from
             social_user_info
        where
             user_id = ?"""
    val connectionsSQL = """
        select
             social_user_1, social_user_2
        from
             (%s) as suid, social_connection as sc
        where
             ( (sc.social_user_1 in (suid.id)) or (sc.social_user_2 in (suid.id)) )
             and sc.state = 'active'
      """.format(suidSQL)
    val sql = """
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
             (sui.user_id != ?)
      """.format(connectionsSQL)
    //can use GetResult and SetParameter to be type safe, not sure its worth it at this point
    val q = StaticQuery.query[(Long, Long), Long](sql)
    val res: Seq[Long] = q.list(id.id, id.id)
    res map {id => Id[User](id)} toSet
  }

  def getConnectionOpt(u1: Id[SocialUserInfo], u2: Id[SocialUserInfo] )(implicit session: RSession): Option[SocialConnection] =
    (for {
      s <- table
      if ((s.socialUser1 === u1 && s.socialUser2 === u2) || (s.socialUser1 === u2 && s.socialUser2 === u1))
    } yield s).firstOption


  def getUserConnections(id: Id[User])(implicit session: RSession): Seq[SocialUserInfo] = {
    socialRepo.getByUser(id).map(_.id.get) match {
      case ids if !ids.isEmpty =>
        val connections = (for {
          t <- table if ((t.socialUser1 inSet ids) || (t.socialUser2 inSet ids)) && t.state === SocialConnectionStates.ACTIVE
        } yield t ).list
        connections map (s => if(ids.contains(s.socialUser1)) s.socialUser2 else s.socialUser1 ) match {
          case users if !users.isEmpty =>
            (for (t <- socialRepo.table if t.id inSet users) yield t).list
          case _ => Nil
        }
      case _ => Nil
    }
  }

  def getSocialUserConnections(id: Id[SocialUserInfo])(implicit session: RSession): Seq[SocialUserInfo] = {
    val connections = (for {
      t <- table if ((t.socialUser1 === id || t.socialUser2 === id) && t.state === SocialConnectionStates.ACTIVE)
    } yield t ).list
    connections map (s => if(id == s.socialUser1) s.socialUser2 else s.socialUser1 ) match {
      case users if !users.isEmpty =>
        (for (t <- socialRepo.table if t.id inSet users) yield t).list
      case _ => Nil
    }
  }
}

object SocialConnectionStates extends States[SocialConnection]
