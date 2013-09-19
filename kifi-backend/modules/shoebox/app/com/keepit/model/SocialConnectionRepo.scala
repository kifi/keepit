package com.keepit.model

import scala.concurrent.duration.Duration

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, Key}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.slick._
import com.keepit.common.time.Clock
import com.keepit.social._

import play.api.libs.json._

@ImplementedBy(classOf[SocialConnectionRepoImpl])
trait SocialConnectionRepo extends Repo[SocialConnection] {
  def getFortyTwoUserConnections(id: Id[User])(implicit session: RSession): Set[Id[User]]
  def getConnectionOpt(u1: Id[SocialUserInfo], u2: Id[SocialUserInfo] )(implicit session: RSession): Option[SocialConnection]
  def getUserConnections(id: Id[User])(implicit session: RSession): Seq[SocialUserInfo]
  def getSocialUserConnections(id: Id[SocialUserInfo])(implicit session: RSession): Seq[SocialUserInfo]
  def getSocialConnectionInfo(id: Id[SocialUserInfo])(implicit session: RSession): Seq[SocialConnectionInfo]
  def deactivateAllConnections(id: Id[SocialUserInfo])(implicit session: RWSession): Int
}

case class SocialUserConnectionsKey(id: Id[SocialUserInfo]) extends Key[Seq[SocialConnectionInfo]] {
  val namespace = "social_user_connections"
  override val version = 2
  def toKey(): String = id.id.toString
}

class SocialUserConnectionsCache(inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
    extends JsonCacheImpl[SocialUserConnectionsKey, Seq[SocialConnectionInfo]](inner, outer: _*)

case class SocialConnectionInfo(
  id: Id[SocialUserInfo],
  userId: Option[Id[User]],
  fullName: String,
  pictureUrl: Option[String],
  socialId: SocialId,
  networkType: SocialNetworkType) {

  def getPictureUrl(preferredWidth: Int = 50, preferredHeight: Int = 50): Option[String] = networkType match {
    case SocialNetworks.FACEBOOK =>
      Some(s"http://graph.facebook.com/$socialId/picture?width=$preferredWidth&height=$preferredHeight")
    case _ => pictureUrl
  }
}

object SocialConnectionInfo {
  // This is an intentionally compact representation to support storing large social graphs
  implicit val format = Format[SocialConnectionInfo](
    __.read[Seq[JsValue]].map {
      case Seq(JsNumber(id), JsNumber(userId), JsString(fullName), JsString(pictureUrl), JsString(socialId), JsString(networkType)) =>
        SocialConnectionInfo(
          Id[SocialUserInfo](id.toLong),
          Some(userId).filter(_ != 0).map(id => Id[User](id.toLong)),
          fullName,
          Some(pictureUrl).filterNot(_.isEmpty),
          SocialId(socialId),
          SocialNetworkType(networkType))
    },
    new Writes[SocialConnectionInfo] {
      def writes(o: SocialConnectionInfo): JsValue =
        Json.arr(o.id.id, o.userId.getOrElse(Id(0)).id, o.fullName, o.pictureUrl.getOrElse[String](""), o.socialId.id, o.networkType.name)
    })

  def fromSocialUser(sui: SocialUserInfo): SocialConnectionInfo =
    SocialConnectionInfo(sui.id.get, sui.userId, sui.fullName, sui.pictureUrl, sui.socialId, sui.networkType)
}

@Singleton
class SocialConnectionRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  socialUserConnectionsCache: SocialUserConnectionsCache,
  socialRepo: SocialUserInfoRepoImpl)
  extends DbRepo[SocialConnection] with SocialConnectionRepo {

  import FortyTwoTypeMappers._
  import scala.slick.jdbc.StaticQuery
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[SocialConnection](db, "social_connection") {
    def socialUser1 = column[Id[SocialUserInfo]]("social_user_1", O.NotNull)
    def socialUser2 = column[Id[SocialUserInfo]]("social_user_2", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ socialUser1 ~ socialUser2 ~ state <> (SocialConnection, SocialConnection.unapply _)
  }

  override def invalidateCache(conn: SocialConnection)(implicit session: RSession): SocialConnection = {
    socialUserConnectionsCache.remove(SocialUserConnectionsKey(conn.socialUser1))
    socialUserConnectionsCache.remove(SocialUserConnectionsKey(conn.socialUser2))
    super.invalidateCache(conn)
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

  def getSocialConnectionInfo(id: Id[SocialUserInfo])(implicit session: RSession): Seq[SocialConnectionInfo] = {
    socialUserConnectionsCache.getOrElse(SocialUserConnectionsKey(id)) {
      getSocialUserConnections(id) map SocialConnectionInfo.fromSocialUser
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

  def deactivateAllConnections(id: Id[SocialUserInfo])(implicit session: RWSession): Int = {
    (for {
      t <- table if (t.socialUser1 === id || t.socialUser2 === id) && t.state === SocialConnectionStates.ACTIVE
    } yield t.state).update(SocialConnectionStates.INACTIVE)
  }
}
