package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ ExternalId, State, Id }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.time.Clock
import com.keepit.common.net.UserAgent
import org.joda.time.DateTime

@ImplementedBy(classOf[KifiInstallationRepoImpl])
trait KifiInstallationRepo extends Repo[KifiInstallation] with ExternalIdColumnFunction[KifiInstallation] {
  def getLatestActiveExtensionVersions(count: Int)(implicit session: RSession): Seq[(KifiExtVersion, DateTime, Int)]
  def getExtensionUserIdsUpdatedSince(when: DateTime)(implicit session: RSession): Set[Id[User]]
  def all(userId: Id[User], excludeState: Option[State[KifiInstallation]] = Some(KifiInstallationStates.INACTIVE))(implicit session: RSession): Seq[KifiInstallation]
  def getOpt(userId: Id[User], externalId: ExternalId[KifiInstallation])(implicit session: RSession): Option[KifiInstallation]
}

@Singleton
class KifiInstallationRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock, val versionCache: ExtensionVersionInstallationIdCache)
    extends DbRepo[KifiInstallation] with KifiInstallationRepo with ExternalIdColumnDbFunction[KifiInstallation] {
  import db.Driver.simple._
  import DBSession._

  type RepoImpl = KifiInstallationTable
  class KifiInstallationTable(tag: Tag) extends RepoTable[KifiInstallation](db, tag, "kifi_installation") with ExternalIdColumn[KifiInstallation] {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def version = column[String]("version", O.NotNull)
    def userAgent = column[UserAgent]("user_agent", O.NotNull)
    def platform = column[String]("platform", O.NotNull)
    def * = (id.?, createdAt, updatedAt, userId, externalId, version, userAgent, platform, state) <> (rowToObj, objToRow)
  }

  private val rowToObj: ((Option[Id[KifiInstallation]], DateTime, DateTime, Id[User], ExternalId[KifiInstallation], String, UserAgent, String, State[KifiInstallation])) => KifiInstallation = {
    case (id, createdAt, updatedAt, userId, externalId, version, userAgent, platform, state) => {
      val kifiInstallationPlatform = KifiInstallationPlatform(platform)
      val kifiVersion: KifiVersion = kifiInstallationPlatform match {
        case KifiInstallationPlatform.IPhone => KifiIPhoneVersion(version)
        case KifiInstallationPlatform.Android => KifiAndroidVersion(version)
        case KifiInstallationPlatform.Extension => KifiExtVersion(version)
      }
      KifiInstallation(id, createdAt, updatedAt, userId, externalId, kifiVersion, userAgent, kifiInstallationPlatform, state)
    }
  }

  private val objToRow: KifiInstallation => Option[(Option[Id[KifiInstallation]], DateTime, DateTime, Id[User], ExternalId[KifiInstallation], String, UserAgent, String, State[KifiInstallation])] = {
    case KifiInstallation(id, createdAt, updatedAt, userId, externalId, version, userAgent, platform, state) =>
      Some((id, createdAt, updatedAt, userId, externalId, version.toString, userAgent, platform.name, state))
    case _ => None
  }

  def table(tag: Tag) = new KifiInstallationTable(tag)
  initTable()

  def getLatestActiveExtensionVersions(count: Int)(implicit session: RSession): Seq[(KifiExtVersion, DateTime, Int)] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    // select version,min(updated_at) as min, count(*) as count from kifi_installation group by version having count > 3 order by min desc limit 20;
    val interpolated = sql"""select version, min(updated_at) as min, count(*) as c from kifi_installation where platform = '#${KifiInstallationPlatform.Extension.name}' group by version order by min desc limit $count"""
    interpolated.as[(String, DateTime, Int)].list.map {
      case (versionStr, max, count) =>
        (KifiExtVersion(versionStr), max, count)
    }.sortWith((a, b) => a._1 > b._1)
  }

  def getExtensionUserIdsUpdatedSince(when: DateTime)(implicit session: RSession): Set[Id[User]] = {
    (for (k <- rows if k.platform === KifiInstallationPlatform.Extension.name && k.state =!= KifiInstallationStates.INACTIVE && k.updatedAt >= when) yield k.userId).list.toSet
    // select count(distinct user_id) from kifi_installation where platform = 'extension' and updated_at >= '2014-06-01';
  }

  def all(userId: Id[User], excludeState: Option[State[KifiInstallation]] = Some(KifiInstallationStates.INACTIVE))(implicit session: RSession): Seq[KifiInstallation] =
    (for (k <- rows if k.userId === userId && k.state =!= excludeState.orNull) yield k).list

  def getOpt(userId: Id[User], externalId: ExternalId[KifiInstallation])(implicit session: RSession): Option[KifiInstallation] =
    (for (k <- rows if k.userId === userId && k.externalId === externalId) yield k).firstOption

  override def invalidateCache(kifiInstallation: KifiInstallation)(implicit session: RSession): Unit = {
    versionCache.set(ExtensionVersionInstallationIdKey(kifiInstallation.externalId), kifiInstallation.version.toString)
  }

  override def deleteCache(model: KifiInstallation)(implicit session: RSession): Unit = {
    versionCache.remove(ExtensionVersionInstallationIdKey(model.externalId))
  }
}
