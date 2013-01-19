package com.keepit.model

import play.api.Play.current
import org.scalaquery.simple.{GetResult, StaticQuery => Q}
import org.scalaquery.simple.GetResult._
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.inject._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
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
  state: State[SocialConnection] = SocialConnectionStates.ACTIVE
) extends Model[SocialConnection] {
  def withId(id: Id[SocialConnection]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[SocialConnection]) = copy(state = state)

  def save(implicit conn: Connection): SocialConnection = {
    val entity = SocialConnectionEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }
}

@ImplementedBy(classOf[SocialConnectionRepoImpl])
trait SocialConnectionRepo extends Repo[SocialConnection] {
  def getFortyTwoUserConnections(id: Id[User])(implicit session: RSession): Set[Id[User]]
}

@Singleton
class SocialConnectionRepoImpl @Inject() (val db: DataBaseComponent) extends DbRepo[SocialConnection] with SocialConnectionRepo {
  import FortyTwoTypeMappers._
  import org.scalaquery.ql._
  import org.scalaquery.ql.ColumnOps._
  import org.scalaquery.ql.basic.BasicProfile
  import org.scalaquery.ql.extended.ExtendedTable
  import db.Driver.Implicit._
  import DBSession._

  override lazy val table = new RepoTable[SocialConnection](db, "social_connection") {
    def socialUser1 = column[Id[SocialUserInfo]]("social_user_1", O.NotNull)
    def socialUser2 = column[Id[SocialUserInfo]]("social_user_1", O.NotNull)
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
    val q = Q.query[(Long, Long), Long](sql)
    val res: Seq[Long] = q.list(id.id, id.id)
    res map {id => Id[User](id)} toSet
  }

  def getConnectionOpt(u1: Id[SocialUserInfo], u2: Id[SocialUserInfo] )(implicit session: RSession): Option[SocialConnection] =
    (for {
      s <- table
      if ((s.socialUser1 === u1 && s.socialUser2 === u2) || (s.socialUser1 === u2 && s.socialUser2 === u1))
    } yield s).firstOption


//  def getUserConnectionsCount(id: Id[User])(implicit session: RSession): Long = {
//    val suis = inject[SocialUserInfoRepo].getByUser(id).map(_.id.get)
//    if(!suis.isEmpty) {
//      (SocialConnectionEntity AS "sc").map { sc =>
//        SELECT (COUNT(sc.id)) FROM sc WHERE (((sc.socialUser1 IN (suis)) OR (sc.socialUser2 IN (suis)))
//          AND (sc.state EQ SocialConnectionStates.ACTIVE) ) unique
//      } get
//    } else {
//      0L
//    }
//  }
//
//  def getUserConnections(id: Id[User])(implicit session: RSession): Seq[SocialUserInfo] = {
//    val suis = SocialUserInfoCxRepo.getByUser(id).map(_.id.get)
//    if(!suis.isEmpty) {
//      val conns = (SocialConnectionEntity AS "sc").map { sc =>
//        SELECT (sc.*) FROM sc WHERE (((sc.socialUser1 IN (suis)) OR (sc.socialUser2 IN (suis))) AND (sc.state EQ SocialConnectionStates.ACTIVE)) list
//      } map (_.view) map (s => if(suis.contains(s.socialUser1)) s.socialUser2 else s.socialUser1 ) toList
//
//      if(!conns.isEmpty) {
//        (SocialUserInfoEntity AS "sui").map { sui =>
//          SELECT (sui.*) FROM sui WHERE (sui.id IN(conns)) list
//        } map (_.view)
//      }
//      else  {
//        Nil
//      }
//    }
//    else {
//      Nil
//    }
//  }
//
//  def getSocialUserConnections(id: Id[SocialUserInfo])(implicit session: RSession): Seq[SocialUserInfo] = {
//
//    val conns = (SocialConnectionEntity AS "sc").map { sc =>
//      SELECT (sc.*) FROM sc WHERE (((sc.socialUser1 EQ id) OR (sc.socialUser2 EQ id)) AND (sc.state EQ SocialConnectionStates.ACTIVE) ) list
//    } map (_.view) map (s => if(id == s.socialUser1) s.socialUser2 else s.socialUser1 ) toList
//
//    if(!conns.isEmpty) {
//      (SocialUserInfoEntity AS "sui").map { sui =>
//        SELECT (sui.*) FROM sui WHERE (sui.id IN(conns)) list
//      } map (_.view)
//    }
//    else {
//      Nil
//    }
//  }
}



object SocialConnectionCxRepo extends Logging {

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
             ( (sc.social_user_1 in (suid.id)) or (sc.social_user_2 in (suid.id)) )
             and sc.state = 'active'    """.format(suidSQL)
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

  def getConnectionOpt(u1: Id[SocialUserInfo], u2: Id[SocialUserInfo] )(implicit conn: Connection): Option[SocialConnection] = {
    (SocialConnectionEntity AS "sc").map { sc => SELECT (sc.*) FROM sc WHERE (
        (((sc.socialUser1 EQ u1) AND (sc.socialUser2 EQ u2)) OR
        ((sc.socialUser1 EQ u2) AND (sc.socialUser2 EQ u1)))  ) unique } map ( _.view )
  }

  def getUserConnectionsCount(id: Id[User])(implicit conn: Connection): Long = {
    val suis = SocialUserInfoCxRepo.getByUser(id).map(_.id.get)
    if(!suis.isEmpty) {
      (SocialConnectionEntity AS "sc").map { sc =>
        SELECT (COUNT(sc.id)) FROM sc WHERE (((sc.socialUser1 IN (suis)) OR (sc.socialUser2 IN (suis)))
          AND (sc.state EQ SocialConnectionStates.ACTIVE) ) unique
      } get
    } else {
      0L
    }
  }

  def getUserConnections(id: Id[User])(implicit conn: Connection): Seq[SocialUserInfo] = {
    val suis = SocialUserInfoCxRepo.getByUser(id).map(_.id.get)
    if(!suis.isEmpty) {
      val conns = (SocialConnectionEntity AS "sc").map { sc =>
        SELECT (sc.*) FROM sc WHERE (((sc.socialUser1 IN (suis)) OR (sc.socialUser2 IN (suis))) AND (sc.state EQ SocialConnectionStates.ACTIVE)) list
      } map (_.view) map (s => if(suis.contains(s.socialUser1)) s.socialUser2 else s.socialUser1 ) toList

      if(!conns.isEmpty) {
        (SocialUserInfoEntity AS "sui").map { sui =>
          SELECT (sui.*) FROM sui WHERE (sui.id IN(conns)) list
        } map (_.view)
      }
      else  {
        Nil
      }
    }
    else {
      Nil
    }
  }

  def getSocialUserConnections(id: Id[SocialUserInfo])(implicit conn: Connection): Seq[SocialUserInfo] = {

    val conns = (SocialConnectionEntity AS "sc").map { sc =>
      SELECT (sc.*) FROM sc WHERE (((sc.socialUser1 EQ id) OR (sc.socialUser2 EQ id)) AND (sc.state EQ SocialConnectionStates.ACTIVE) ) list
    } map (_.view) map (s => if(id == s.socialUser1) s.socialUser2 else s.socialUser1 ) toList

    if(!conns.isEmpty) {
      (SocialUserInfoEntity AS "sui").map { sui =>
        SELECT (sui.*) FROM sui WHERE (sui.id IN(conns)) list
      } map (_.view)
    }
    else {
      Nil
    }
  }
}

object SocialConnectionStates {
  val ACTIVE = State[SocialConnection]("active")
  val INACTIVE = State[SocialConnection]("inactive")
}

private[model] class SocialConnectionEntity extends Entity[SocialConnection, SocialConnectionEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val socialUser1 = "social_user_1".ID[SocialUserInfo].NOT_NULL
  val socialUser2 = "social_user_2".ID[SocialUserInfo].NOT_NULL
  val state = "state".STATE[SocialConnection].NOT_NULL(SocialConnectionStates.ACTIVE)

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


