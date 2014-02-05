package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.{ExternalId, State, Id}
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.time.{Clock, zones}
import com.keepit.common.net.UserAgent
import scala.slick.jdbc.GetResult
import org.joda.time.DateTime
import scala.slick.session.PositionedResult

@ImplementedBy(classOf[KifiInstallationRepoImpl])
trait KifiInstallationRepo extends Repo[KifiInstallation] with ExternalIdColumnFunction[KifiInstallation] {
  def all(userId: Id[User], excludeState: Option[State[KifiInstallation]] = Some(KifiInstallationStates.INACTIVE))(implicit session: RSession): Seq[KifiInstallation]
  def getOpt(userId: Id[User], externalId: ExternalId[KifiInstallation])(implicit session: RSession): Option[KifiInstallation]
  def getLatestActive(count: Int)(implicit session: RSession): Seq[(KifiVersion, DateTime, Int)]
}

@Singleton
class KifiInstallationRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock, val versionCache: ExtensionVersionInstallationIdCache)
  extends DbRepo[KifiInstallation] with KifiInstallationRepo with ExternalIdColumnDbFunction[KifiInstallation] {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[KifiInstallation](db, "kifi_installation") with NamedColumns with ExternalIdColumn[KifiInstallation] {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def version = column[KifiVersion]("version", O.NotNull)
    def userAgent = column[UserAgent]("user_agent", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ externalId ~ version ~ userAgent ~ state <> (KifiInstallation, KifiInstallation.unapply _)
  }

  def all(userId: Id[User], excludeState: Option[State[KifiInstallation]] = Some(KifiInstallationStates.INACTIVE))(implicit session: RSession): Seq[KifiInstallation] =
    (for(k <- table if k.userId === userId && k.state =!= excludeState.orNull) yield k).list

  def getOpt(userId: Id[User], externalId: ExternalId[KifiInstallation])(implicit session: RSession): Option[KifiInstallation] =
    (for(k <- table if k.userId === userId && k.externalId === externalId) yield k).firstOption

  implicit val GetDateTime: GetResult[DateTime] = new GetResult[DateTime] {
    def apply(r: PositionedResult) = new DateTime(r.nextTimestamp getTime, zones.UTC)
  }

  def getLatestActive(count: Int)(implicit session: RSession): Seq[(KifiVersion, DateTime, Int)] = {
    import scala.slick.jdbc.StaticQuery.interpolation
    // select version,min(updated_at) as min, count(*) as count from kifi_installation group by version having count > 3 order by min desc limit 20;
    val interpolated = sql"""select version, min(updated_at) as min, count(*) as c from kifi_installation group by version order by min desc limit $count"""
    interpolated.as[(String, DateTime, Int)].list().map { case (versionStr, max, count) =>
      (KifiVersion(versionStr), max, count)
    }.sortWith((a,b) => a._1 > b._1)
  }

  override def invalidateCache(kifiInstallation: KifiInstallation)(implicit session: RSession): Unit = {
    versionCache.set(ExtensionVersionInstallationIdKey(kifiInstallation.externalId), kifiInstallation.version.toString)
  }

  override def deleteCache(model: KifiInstallation)(implicit session: RSession): Unit = {
    versionCache.remove(ExtensionVersionInstallationIdKey(model.externalId))
  }
}
