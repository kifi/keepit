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
class KifiInstallationRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[KifiInstallation] with KifiInstallationRepo with ExternalIdColumnDbFunction[KifiInstallation] {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[KifiInstallation](db, "kifi_installation") with ExternalIdColumn[KifiInstallation] {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def version = column[KifiVersion]("version", O.NotNull)
    def userAgent = column[UserAgent]("user_agent", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ externalId ~ version ~ userAgent ~ state <> (KifiInstallation, KifiInstallation.unapply _)
  }

  def all(userId: Id[User], excludeState: Option[State[KifiInstallation]] = Some(KifiInstallationStates.INACTIVE))(implicit session: RSession): Seq[KifiInstallation] =
    (for(k <- table if k.userId === userId && k.state =!= excludeState.orNull) yield k).list

  def getOpt(userId: Id[User], externalId: ExternalId[KifiInstallation])(implicit session: RSession): Option[KifiInstallation] =
    (for(k <- table if k.userId === userId && k.externalId === externalId) yield k).firstOption
}
