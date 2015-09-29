package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ Id, State }
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import org.joda.time.DateTime

@ImplementedBy(classOf[OrganizationConfigurationRepoImpl])
trait OrganizationConfigurationRepo extends Repo[OrganizationConfiguration] {
  def getByOrgId(orgId: Id[Organization])(implicit session: RSession): OrganizationConfiguration
  def deactivate(model: OrganizationConfiguration)(implicit session: RWSession): OrganizationConfiguration
}

@Singleton
class OrganizationConfigurationRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends OrganizationConfigurationRepo with DbRepo[OrganizationConfiguration] {

  override def deleteCache(orgMember: OrganizationConfiguration)(implicit session: RSession): Unit = {}
  override def invalidateCache(orgMember: OrganizationConfiguration)(implicit session: RSession): Unit = {}

  import db.Driver.simple._

  def applyFromDbRow(
    id: Option[Id[OrganizationConfiguration]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[OrganizationConfiguration],
    organizationId: Id[Organization],
    settings: OrganizationSettings) = {
    OrganizationConfiguration(id, createdAt, updatedAt, state, organizationId, settings)
  }

  def unapplyToDbRow(config: OrganizationConfiguration) = {
    Some((
      config.id,
      config.createdAt,
      config.updatedAt,
      config.state,
      config.organizationId,
      config.settings
    ))
  }

  type RepoImpl = OrganizationConfigurationTable
  class OrganizationConfigurationTable(tag: Tag) extends RepoTable[OrganizationConfiguration](db, tag, "organization_configuration") with NamedColumns {
    def organizationId = column[Id[Organization]]("organization_id", O.NotNull)
    def settings = column[OrganizationSettings]("settings", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, organizationId, settings) <> ((applyFromDbRow _).tupled, unapplyToDbRow _)
  }

  def table(tag: Tag) = new OrganizationConfigurationTable(tag)
  initTable()

  def getByOrgId(orgId: Id[Organization])(implicit session: RSession): OrganizationConfiguration = {
    rows.filter(_.organizationId === orgId).first
  }
  def deactivate(model: OrganizationConfiguration)(implicit session: RWSession): OrganizationConfiguration = {
    save(model.sanitizeForDelete)
  }
}
