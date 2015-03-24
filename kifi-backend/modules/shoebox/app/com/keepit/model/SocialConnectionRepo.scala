package com.keepit.model

import com.google.inject.{ Provider, Inject, Singleton, ImplementedBy }
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, Key, CacheStatistics }
import com.keepit.common.logging.AccessLog
import com.keepit.common.db.{ DbSequenceAssigner, State, SequenceNumber, Id }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.time.Clock
import com.keepit.social.{ SocialNetworks, SocialNetworkType }
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import com.keepit.common.plugin.{ SchedulingProperties, SequencingActor, SequencingPlugin }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.healthcheck.AirbrakeNotifier

@ImplementedBy(classOf[SocialConnectionRepoImpl])
trait SocialConnectionRepo extends Repo[SocialConnection] with SeqNumberFunction[SocialConnection] {
  def getSociallyConnectedUsers(id: Id[User])(implicit session: RSession): Set[Id[User]]
  def getConnectionOpt(u1: Id[SocialUserInfo], u2: Id[SocialUserInfo])(implicit session: RSession): Option[SocialConnection]
  def getSocialConnectionInfos(id: Id[SocialUserInfo])(implicit session: RSession): Seq[SocialUserBasicInfo]
  def getSocialConnectionInfosByUser(id: Id[User])(implicit session: RSession): Map[SocialNetworkType, Seq[SocialUserBasicInfo]]
  def deactivateAllConnections(id: Id[SocialUserInfo])(implicit session: RWSession): Int
  def getUserConnectionCount(id: Id[User])(implicit session: RSession): Int
  def getConnAndNetworkBySeqNumber(lowerBound: SequenceNumber[SocialConnection], fetchSize: Int = -1)(implicit session: RSession): Seq[(Id[SocialUserInfo], Id[SocialUserInfo], State[SocialConnection], SequenceNumber[SocialConnection], SocialNetworkType)]
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
  override protected val changeListener: Option[RepoModification.Listener[SocialConnection]],
  socialUserConnectionsCache: SocialUserConnectionsCache,
  socialRepo: SocialUserInfoRepoImpl)
    extends DbRepo[SocialConnection] with SeqNumberDbFunction[SocialConnection] with SocialConnectionRepo {

  import scala.slick.jdbc.StaticQuery
  import db.Driver.simple._
  import DBSession._

  type RepoImpl = SocialConnectionTable
  case class SocialConnectionTable(tag: Tag) extends RepoTable[SocialConnection](db, tag, "social_connection") with SeqNumberColumn[SocialConnection] {
    def socialUser1 = column[Id[SocialUserInfo]]("social_user_1", O.NotNull)
    def socialUser2 = column[Id[SocialUserInfo]]("social_user_2", O.NotNull)
    def * = (id.?, createdAt, updatedAt, socialUser1, socialUser2, state, seq) <> (SocialConnection.tupled, SocialConnection.unapply _)
  }

  def table(tag: Tag) = new SocialConnectionTable(tag)

  override def save(socialConnection: SocialConnection)(implicit session: RWSession): SocialConnection = {
    val toSave = socialConnection.copy(seq = deferredSeqNum())
    super.save(toSave)
  }

  override def invalidateCache(conn: SocialConnection)(implicit session: RSession): Unit = {
    socialUserConnectionsCache.remove(SocialUserConnectionsKey(conn.socialUser1))
    socialUserConnectionsCache.remove(SocialUserConnectionsKey(conn.socialUser2))
  }

  override def deleteCache(conn: SocialConnection)(implicit session: RSession): Unit = {
    socialUserConnectionsCache.remove(SocialUserConnectionsKey(conn.socialUser1))
    socialUserConnectionsCache.remove(SocialUserConnectionsKey(conn.socialUser2))
  }

  def getSociallyConnectedUsers(id: Id[User])(implicit session: RSession): Set[Id[User]] = {
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
    val res: Seq[Long] = q.apply(id.id, id.id).list
    res map { id => Id[User](id) } toSet
  }

  def getConnectionOpt(u1: Id[SocialUserInfo], u2: Id[SocialUserInfo])(implicit session: RSession): Option[SocialConnection] =
    (for {
      s <- rows
      if ((s.socialUser1 === u1 && s.socialUser2 === u2) || (s.socialUser1 === u2 && s.socialUser2 === u1))
    } yield s).firstOption

  def getUserConnectionCount(id: Id[User])(implicit session: RSession): Int = {
    socialRepo.getByUser(id).map(_.id.get) match {
      case ids if ids.nonEmpty => {
        val q = Query(
          (for {
            t <- rows if ((t.socialUser1 inSet ids) || (t.socialUser2 inSet ids)) && t.state === SocialConnectionStates.ACTIVE
          } yield t).length
        )
        q.first
      }
      case _ => 0
    }
  }

  def getSocialConnectionInfos(id: Id[SocialUserInfo])(implicit session: RSession): Seq[SocialUserBasicInfo] = {
    socialUserConnectionsCache.getOrElse(SocialUserConnectionsKey(id)) {
      val socialUserInfoIds = getSocialUserConnections(id)
      socialRepo.getSocialUserBasicInfos(socialUserInfoIds).values.toSeq
    }
  }

  private[this] val consolidateSocialConnectionReq = new RequestConsolidator[Id[User], Map[SocialNetworkType, Seq[SocialUserBasicInfo]]](10 seconds)

  def getSocialConnectionInfosByUser(userId: Id[User])(implicit s: RSession): Map[SocialNetworkType, Seq[SocialUserBasicInfo]] = {
    val future = consolidateSocialConnectionReq(userId) { userId =>
      Future.successful(
        socialRepo.getByUser(userId)
          .filter { sui => sui.networkType != SocialNetworks.FORTYTWO }
          .map { sui => (sui.networkType -> getSocialConnectionInfos(sui.id.get)) }.toMap
      )
    }
    Await.result(future, Duration.Inf)
  }

  private def getSocialUserConnections(id: Id[SocialUserInfo])(implicit session: RSession): Seq[Id[SocialUserInfo]] = {
    val connections = (for {
      t <- rows if (t.socialUser1 === id || t.socialUser2 === id) && t.state === SocialConnectionStates.ACTIVE
    } yield t).list
    connections map (s => if (id == s.socialUser1) s.socialUser2 else s.socialUser1)
  }

  def deactivateAllConnections(id: Id[SocialUserInfo])(implicit session: RWSession): Int = {
    val conns = for {
      t <- rows if (t.socialUser1 === id || t.socialUser2 === id) && t.state === SocialConnectionStates.ACTIVE
    } yield t
    val updatedRows = conns.map(_.state).update(SocialConnectionStates.INACTIVE)
    conns.list.map(invalidateCache)
    updatedRows
  }

  def getConnAndNetworkBySeqNumber(lowerBound: SequenceNumber[SocialConnection], fetchSize: Int = -1)(implicit session: RSession): Seq[(Id[SocialUserInfo], Id[SocialUserInfo], State[SocialConnection], SequenceNumber[SocialConnection], SocialNetworkType)] = {
    import StaticQuery.interpolation
    val query = if (fetchSize > 0) { // unfortunately no easy way to make dynamic parts of interpolated sql queries
      sql"""select sc.social_user_1, sc.social_user_2, sc.state, sc.seq, sui.network_type
            from social_connection sc join social_user_info sui on sc.social_user_1 = sui.id
            where sc.seq > ${lowerBound.value} limit $fetchSize"""
    } else {
      sql"""select sc.social_user_1, sc.social_user_2, sc.state, sc.seq, sui.network_type
            from social_connection sc join social_user_info sui on sc.social_user_1 = sui.id
            where sc.seq > ${lowerBound.value}"""
    }

    query.as[(Id[SocialUserInfo], Id[SocialUserInfo], State[SocialConnection], SequenceNumber[SocialConnection], SocialNetworkType)].list
  }
}

trait SocialConnectionSequencingPlugin extends SequencingPlugin

class SocialConnectionSequencingPluginImpl @Inject() (
    override val actor: ActorInstance[SocialConnectionSequencingActor],
    override val scheduling: SchedulingProperties) extends SocialConnectionSequencingPlugin {

  override val interval: FiniteDuration = 20 seconds
}

@Singleton
class SocialConnectionSequenceNumberAssigner @Inject() (db: Database, repo: SocialConnectionRepo, airbrake: AirbrakeNotifier)
  extends DbSequenceAssigner[SocialConnection](db, repo, airbrake)

class SocialConnectionSequencingActor @Inject() (
  assigner: SocialConnectionSequenceNumberAssigner,
  airbrake: AirbrakeNotifier) extends SequencingActor(assigner, airbrake)
