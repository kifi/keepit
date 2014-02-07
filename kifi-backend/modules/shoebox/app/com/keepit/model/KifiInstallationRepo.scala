package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.{ExternalId, State, Id}
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.time.Clock
import scala.Some
import com.keepit.common.net.UserAgent

@ImplementedBy(classOf[KifiInstallationRepoImpl])
trait KifiInstallationRepo extends Repo[KifiInstallation] with ExternalIdColumnFunction[KifiInstallation] {
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
    def version = column[KifiVersion]("version", O.NotNull)
    def userAgent = column[UserAgent]("user_agent", O.NotNull)
    def * = (id.?, createdAt, updatedAt, userId, externalId, version, userAgent, state) <> ((KifiInstallation.apply _).tupled, KifiInstallation.unapply _)
  }

  def table(tag: Tag) = new KifiInstallationTable(tag)
  initTable()

  def all(userId: Id[User], excludeState: Option[State[KifiInstallation]] = Some(KifiInstallationStates.INACTIVE))(implicit session: RSession): Seq[KifiInstallation] =
    (for(k <- rows if k.userId === userId && k.state =!= excludeState.orNull) yield k).list

  def getOpt(userId: Id[User], externalId: ExternalId[KifiInstallation])(implicit session: RSession): Option[KifiInstallation] =
    (for(k <- rows if k.userId === userId && k.externalId === externalId) yield k).firstOption

  override def invalidateCache(kifiInstallation: KifiInstallation)(implicit session: RSession): Unit = {
    versionCache.set(ExtensionVersionInstallationIdKey(kifiInstallation.externalId), kifiInstallation.version.toString)
  }

  override def deleteCache(model: KifiInstallation)(implicit session: RSession): Unit = {
    versionCache.remove(ExtensionVersionInstallationIdKey(model.externalId))
  }
}
