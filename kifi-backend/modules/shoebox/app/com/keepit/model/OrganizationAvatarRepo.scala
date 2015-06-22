package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo, Repo }
import com.keepit.common.db.{ Id, State }
import com.keepit.common.store.ImagePath
import com.keepit.common.time.Clock
import org.joda.time.DateTime

@ImplementedBy(classOf[OrganizationAvatarRepoImpl])
trait OrganizationAvatarRepo extends Repo[OrganizationAvatar] {
  def getByOrganization(organizationId: Id[Organization], state: State[OrganizationAvatar] = OrganizationAvatarStates.ACTIVE)(implicit session: RSession): Seq[OrganizationAvatar]
}

@Singleton
class OrganizationAvatarRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends OrganizationAvatarRepo with DbRepo[OrganizationAvatar] {
  override def deleteCache(orgLogo: OrganizationAvatar)(implicit session: RSession) {}
  override def invalidateCache(orgLogo: OrganizationAvatar)(implicit session: RSession) {}

  import db.Driver.simple._

  type RepoImpl = OrganizationAvatarTable
  class OrganizationAvatarTable(tag: Tag) extends RepoTable[OrganizationAvatar](db, tag, "organization_avatar") {
    def organizationId = column[Id[Organization]]("organization_id", O.NotNull)
    def width = column[Int]("width", O.NotNull)
    def height = column[Int]("height", O.NotNull)
    def format = column[ImageFormat]("format", O.NotNull)
    def kind = column[ProcessImageOperation]("kind", O.NotNull)
    def imagePath = column[ImagePath]("path", O.NotNull)
    def source = column[ImageSource]("source", O.NotNull)
    def sourceFileHash = column[ImageHash]("source_file_hash", O.NotNull)
    def sourceImageURL = column[Option[String]]("source_image_url", O.Nullable)

    def applyFromDbRow(
      id: Option[Id[OrganizationAvatar]],
      createdAt: DateTime,
      updatedAt: DateTime,
      state: State[OrganizationAvatar],
      organizationId: Id[Organization],
      width: Int,
      height: Int,
      format: ImageFormat,
      kind: ProcessImageOperation,
      imagePath: ImagePath,
      source: ImageSource,
      sourceFileHash: ImageHash,
      sourceImageURL: Option[String]) = {
      OrganizationAvatar(id, createdAt, updatedAt, state, organizationId, width, height, format, kind, imagePath, source, sourceFileHash, sourceImageURL)
    }

    def unapplyToDbRow(avatar: OrganizationAvatar) = {
      Some((avatar.id,
        avatar.createdAt,
        avatar.updatedAt,
        avatar.state,
        avatar.organizationId,
        avatar.width,
        avatar.height,
        avatar.format,
        avatar.kind,
        avatar.imagePath,
        avatar.source,
        avatar.sourceFileHash,
        avatar.sourceImageURL))
    }

    def * = (id.?, createdAt, updatedAt, state, organizationId, width, height, format, kind, imagePath, source, sourceFileHash, sourceImageURL) <> ((applyFromDbRow _).tupled, unapplyToDbRow _)
  }

  def table(tag: Tag) = new OrganizationAvatarTable(tag)
  initTable()

  def getByOrganizationCompiled = Compiled { (organizationId: Column[Id[Organization]], state: Column[State[OrganizationAvatar]]) =>
    for (row <- rows if row.organizationId === organizationId && row.state === state) yield row
  }

  def getByOrganization(organizationId: Id[Organization], state: State[OrganizationAvatar] = OrganizationAvatarStates.ACTIVE)(implicit session: RSession): Seq[OrganizationAvatar] = {
    getByOrganizationCompiled(organizationId, state).list
  }
}
