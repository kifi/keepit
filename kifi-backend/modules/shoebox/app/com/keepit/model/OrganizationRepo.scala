package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock

@ImplementedBy(classOf[OrganizationRepoImpl])
trait OrganizationRepo extends Repo[Organization] with SeqNumberFunction[Organization] {
}

@Singleton
class OrganizationRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends OrganizationRepo with DbRepo[Organization] with SeqNumberDbFunction[Organization] with Logging {
  override def deleteCache(org: Organization)(implicit session: RSession) {}
  override def invalidateCache(org: Organization)(implicit session: RSession) {}

  import db.Driver.simple._

  type RepoImpl = OrganizationTable
  class OrganizationTable(tag: Tag) extends RepoTable[Organization](db, tag, "organization") with SeqNumberColumn[Organization] {
    def name = column[String]("name", O.NotNull)
    def description = column[Option[String]]("description", O.Nullable)
    def ownerId = column[Id[User]]("owner_id", O.NotNull)
    implicit val organizationSlugMapper = MappedColumnType.base[OrganizationSlug, String](_.value, OrganizationSlug(_))
    def slug = column[OrganizationSlug]("slug", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, seq, name, description, ownerId, slug) <> ((Organization.applyFromDbRow _).tupled, Organization.unapplyToDbRow _)
  }

  def table(tag: Tag) = new OrganizationTable(tag)
  initTable()
}
