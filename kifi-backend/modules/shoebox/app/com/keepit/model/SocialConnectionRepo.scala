package com.keepit.model

import scala.concurrent.duration.Duration

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, Key, CacheStatistics}
import com.keepit.common.logging.AccessLog
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.slick._
import com.keepit.common.time.Clock

@ImplementedBy(classOf[SocialConnectionRepoImpl])
trait SocialConnectionRepo extends Repo[SocialConnection] {
  def getFortyTwoUserConnections(id: Id[User])(implicit session: RSession): Set[Id[User]]
  def getConnectionOpt(u1: Id[SocialUserInfo], u2: Id[SocialUserInfo] )(implicit session: RSession): Option[SocialConnection]
  def getUserConnections(id: Id[User])(implicit session: RSession): Seq[SocialUserInfo]
  def getSocialUserConnections(id: Id[SocialUserInfo])(implicit session: RSession): Seq[SocialUserInfo]
  def getSocialConnectionInfo(id: Id[SocialUserInfo])(implicit session: RSession): Seq[SocialUserBasicInfo]
  def deactivateAllConnections(id: Id[SocialUserInfo])(implicit session: RWSession): Int
  def getUserConnectionCount(id: Id[User])(implicit session: RSession): Int
}

case class SocialUserConnectionsKey(id: Id[SocialUserInfo]) extends Key[Seq[SocialUserBasicInfo]] {
  val namespace = "social_user_connections"
  override val version = 2
  def toKey(): String = id.id.toString
}

// todo(eishay): this cache should be invalidated when a connection is updated in SocialUserInfoRepo, but as it is, it would be very expensive to find all the keys that need invalidation
class SocialUserConnectionsCache(stats: CacheStatistics, accessLog: AccessLog, inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
    extends JsonCacheImpl[SocialUserConnectionsKey, Seq[SocialUserBasicInfo]](stats, accessLog, inner, outer: _*)

@Singleton
class SocialConnectionRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  socialUserConnectionsCache: SocialUserConnectionsCache,
  socialRepo: SocialUserInfoRepoImpl)
  extends DbRepo[SocialConnection] with SocialConnectionRepo {

  import FortyTwoTypeMappers._
  import scala.slick.jdbc.StaticQuery
  import db.Driver.simple._
  import DBSession._

  type RepoImpl = SocialConnectionTable
  case class SocialConnectionTable(tag: Tag) extends RepoTable[SocialConnection](db, tag, "social_connection") {
    def socialUser1 = column[Id[SocialUserInfo]]("social_user_1", O.NotNull)
    def socialUser2 = column[Id[SocialUserInfo]]("social_user_2", O.NotNull)
    def * = (id.?, createdAt, updatedAt, socialUser1, socialUser2, state) <> (SocialConnection.tupled, SocialConnection.unapply _)
  }

  def table(tag: Tag) = new SocialConnectionTable(tag)

  override def invalidateCache(conn: SocialConnection)(implicit session: RSession): Unit = {
    socialUserConnectionsCache.remove(SocialUserConnectionsKey(conn.socialUser1))
    socialUserConnectionsCache.remove(SocialUserConnectionsKey(conn.socialUser2))
  }

  override def deleteCache(conn: SocialConnection)(implicit session: RSession): Unit = {
    socialUserConnectionsCache.remove(SocialUserConnectionsKey(conn.socialUser1))
    socialUserConnectionsCache.remove(SocialUserConnectionsKey(conn.socialUser2))
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
      s <-rows
      if ((s.socialUser1 === u1 && s.socialUser2 === u2) || (s.socialUser1 === u2 && s.socialUser2 === u1))
    } yield s).firstOption


  def getUserConnections(id: Id[User])(implicit session: RSession): Seq[SocialUserInfo] = {
    socialRepo.getByUser(id).map(_.id.get) match {
      case ids if !ids.isEmpty =>
        val connections = (for {
          t <-rows if ((t.socialUser1 inSet ids) || (t.socialUser2 inSet ids)) && t.state === SocialConnectionStates.ACTIVE
        } yield t ).list
        connections map (s => if(ids.contains(s.socialUser1)) s.socialUser2 else s.socialUser1 ) match {
          case users if !users.isEmpty =>
            (for (t <- socialRepo.rows if t.id inSet users) yield t).list
          case _ => Nil
        }
      case _ => Nil
    }
  }

  def getUserConnectionCount(id: Id[User])(implicit session: RSession): Int = {
    socialRepo.getByUser(id).map(_.id.get) match {
      case ids if ids.nonEmpty => {
        val q = Query(
          (for {
            t <-rows if ((t.socialUser1 inSet ids) || (t.socialUser2 inSet ids)) && t.state === SocialConnectionStates.ACTIVE
          } yield t).length
        )
        q.first()
      }
      case _ => 0
    }
  }

  def getSocialConnectionInfo(id: Id[SocialUserInfo])(implicit session: RSession): Seq[SocialUserBasicInfo] = {
    socialUserConnectionsCache.getOrElse(SocialUserConnectionsKey(id)) {
      getSocialUserConnections(id) map SocialUserBasicInfo.fromSocialUser
    }
  }

  def getSocialUserConnections(id: Id[SocialUserInfo])(implicit session: RSession): Seq[SocialUserInfo] = {
    val connections = (for {
      t <-rows if ((t.socialUser1 === id || t.socialUser2 === id) && t.state === SocialConnectionStates.ACTIVE)
    } yield t ).list
    connections map (s => if(id == s.socialUser1) s.socialUser2 else s.socialUser1 ) match {
      case users if !users.isEmpty =>
        (for (t <- socialRepo.rows if t.id inSet users) yield t).list
      case _ => Nil
    }
  }

  def deactivateAllConnections(id: Id[SocialUserInfo])(implicit session: RWSession): Int = {
    val conns = for {
      t <-rows if (t.socialUser1 === id || t.socialUser2 === id) && t.state === SocialConnectionStates.ACTIVE
    } yield t
    val updatedRows = conns.map(_.state).update(SocialConnectionStates.INACTIVE)
    conns.list.map(invalidateCache)
    updatedRows
  }
}
