package com.keepit.model

import com.google.inject.{ Provider, ImplementedBy, Inject, Singleton }
import com.keepit.common.db.{ Id }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import play.api.libs.json.Json

@ImplementedBy(classOf[OrganizationRepoImpl])
trait OrganizationRepo extends Repo[Organization] with SeqNumberFunction[Organization] {
  def updateName(organizationId: Id[Organization], name: String): Organization = ???
  def updateDescription(organizationId: Id[Organization], description: String): Organization = ???
}

@Singleton
class OrganizationRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock, libRepo: Provider[LibraryRepoImpl]) extends OrganizationRepo with DbRepo[Organization] with SeqNumberDbFunction[Organization] with Logging {
  override def deleteCache(org: Organization)(implicit session: RSession) {}
  override def invalidateCache(org: Organization)(implicit session: RSession) {}

  import db.Driver.simple._

  type RepoImpl = OrganizationTable
  class OrganizationTable(tag: Tag) extends RepoTable[Organization](db, tag, "organization") with SeqNumberColumn[Organization] {
    implicit val basePermissionsMapper = MappedColumnType.base[BasePermissions, String](
      { basePermissions => Json.stringify(Json.toJson(basePermissions)) },
      { str => Json.parse(str).as[BasePermissions] }
    )

    def name = column[String]("name", O.NotNull)
    def description = column[Option[String]]("description", O.Nullable)
    def ownerId = column[Id[User]]("owner_id", O.NotNull)
    def organizationHandle = column[Option[OrganizationHandle]]("handle", O.Nullable)
    def normalizedOrganizationHandle = column[Option[OrganizationHandle]]("normalized_handle", O.Nullable)
    def basePermissions = column[BasePermissions]("base_permissions", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, seq, name, description, ownerId, organizationHandle, normalizedOrganizationHandle, basePermissions) <> ((Organization.applyFromDbRow _).tupled, Organization.unapplyToDbRow _)
  }

  def table(tag: Tag) = new OrganizationTable(tag)
  initTable()
}
