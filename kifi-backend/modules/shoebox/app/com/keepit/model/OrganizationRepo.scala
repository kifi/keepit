package com.keepit.model

import com.google.inject.{ Provider, ImplementedBy, Inject, Singleton }
import com.keepit.common.db.{ SequenceNumber, State, Id }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time.Clock
import org.joda.time.DateTime

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

    def name = column[String]("name", O.NotNull)
    def description = column[Option[String]]("description", O.Nullable)
    def ownerId = column[Id[User]]("owner_id", O.NotNull)
    def organizationHandle = column[Option[OrganizationHandle]]("organization_handle", O.Nullable)
    def normalizedOrganizationHandle = column[Option[OrganizationHandle]]("normalized_organization_handle", O.Nullable)

    def applyFromDbRow(
      id: Option[Id[Organization]],
      createdAt: DateTime,
      updatedAt: DateTime,
      state: State[Organization],
      seq: SequenceNumber[Organization],
      name: String,
      description: Option[String],
      ownerId: Id[User],
      organizationHandleOpt: Option[OrganizationHandle],
      normalizedOrganizationHandleOpt: Option[OrganizationHandle]) = {
      val primaryOrganizationHandle = for {
        organizationHandle <- organizationHandleOpt
        normalizedOrganizationHandle <- normalizedOrganizationHandleOpt
      } yield PrimaryOrganizationHandle(organizationHandle, normalizedOrganizationHandle)
      Organization(id, createdAt, updatedAt, state, seq, name, description, ownerId, primaryOrganizationHandle)
    }

    def unapplyToDbRow(org: Organization) = {
      Some((org.id,
        org.createdAt,
        org.updatedAt,
        org.state,
        org.seq,
        org.name,
        org.description,
        org.ownerId,
        org.handle.map(_.original)),
        org.handle.map(_.normalized))
    }

    def * = (id.?, createdAt, updatedAt, state, seq, name, description, ownerId, organizationHandle, normalizedOrganizationHandle) <> ((applyFromDbRow _).tupled, unapplyToDbRow _)
  }

  def table(tag: Tag) = new OrganizationTable(tag)
  initTable()
}
