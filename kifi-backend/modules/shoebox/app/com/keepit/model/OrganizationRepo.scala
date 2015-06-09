package com.keepit.model

import com.google.inject.{ Provider, ImplementedBy, Inject, Singleton }
import com.keepit.common.db.{ Id }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock

import scala.util.{ Success, Failure, Try }

@ImplementedBy(classOf[OrganizationRepoImpl])
trait OrganizationRepo extends Repo[Organization] with SeqNumberFunction[Organization] {
  def updateName(organizationId: Id[Organization], name: String)(implicit session: RWSession): Try[String]
  def updateDescription(organizationId: Id[Organization], description: Option[String])(implicit session: RWSession): Try[String]
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
    def organizationHandle = column[Option[OrganizationHandle]]("handle", O.Nullable)
    def normalizedOrganizationHandle = column[Option[OrganizationHandle]]("normalized_handle", O.Nullable)

    def * = (id.?, createdAt, updatedAt, state, seq, name, description, ownerId, organizationHandle, normalizedOrganizationHandle) <> ((Organization.applyFromDbRow _).tupled, Organization.unapplyToDbRow _)
  }

  def table(tag: Tag) = new OrganizationTable(tag)
  initTable()

  private[this] val updateNameCompiled = Compiled { (orgId: Column[Id[Organization]]) =>
    (for { row <- rows if row.id === orgId } yield row.name)
  }

  class FailedUpdateException extends Exception
  val failedUpdate = new FailedUpdateException()

  override def updateName(organizationId: Id[Organization], name: String)(implicit session: RWSession): Try[String] = {
    updateNameCompiled(organizationId).update(name) match {
      case 0 => Failure(failedUpdate)
      case _ => Success("success")
    }
  }

  private[this] val updateDescriptionCompiled = Compiled { (orgId: Column[Id[Organization]]) =>
    (for { row <- rows if row.id === orgId } yield row.description)
  }

  override def updateDescription(organizationId: Id[Organization], description: Option[String])(implicit session: RWSession): Try[String] = {
    updateDescriptionCompiled(organizationId).update(description) match {
      case 0 => Failure(failedUpdate)
      case _ => Success("success")
    }
  }
}
