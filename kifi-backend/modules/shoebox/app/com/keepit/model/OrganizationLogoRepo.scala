package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo, Repo }
import com.keepit.common.db.{ Id, State }
import com.keepit.common.store.ImagePath
import com.keepit.common.time.Clock
import org.joda.time.DateTime

@ImplementedBy(classOf[OrganizationLogoRepoImpl])
trait OrganizationLogoRepo extends Repo[OrganizationLogo] {
  def getByOrganization(organizationId: Id[Organization]): Seq[OrganizationLogo] = ???
}

@Singleton
class OrganizationLogoRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends OrganizationLogoRepo with DbRepo[OrganizationLogo] {
  override def deleteCache(orgLogo: OrganizationLogo)(implicit session: RSession) {}
  override def invalidateCache(orgLogo: OrganizationLogo)(implicit session: RSession) {}

  import db.Driver.simple._

  type RepoImpl = OrganizationLogoTable
  class OrganizationLogoTable(tag: Tag) extends RepoTable[OrganizationLogo](db, tag, "organization_logo") {
    def organizationId = column[Id[Organization]]("organization_id", O.NotNull)
    def xPosition = column[Option[Int]]("x_position", O.Nullable)
    def yPosition = column[Option[Int]]("y_position", O.Nullable)
    def width = column[Int]("width", O.NotNull)
    def height = column[Int]("height", O.NotNull)
    def format = column[ImageFormat]("format", O.NotNull)
    def kind = column[ProcessImageOperation]("kind", O.NotNull)
    def imagePath = column[ImagePath]("path", O.NotNull)
    def source = column[ImageSource]("source", O.NotNull)
    def sourceFileHash = column[ImageHash]("source_file_hash", O.NotNull)
    def sourceImageURL = column[Option[String]]("source_image_url", O.Nullable)

    def applyFromDbRow(
      id: Option[Id[OrganizationLogo]],
      createdAt: DateTime,
      updatedAt: DateTime,
      state: State[OrganizationLogo],
      organizationId: Id[Organization],
      xPosition: Option[Int],
      yPosition: Option[Int],
      width: Int,
      height: Int,
      format: ImageFormat,
      kind: ProcessImageOperation,
      imagePath: ImagePath,
      source: ImageSource,
      sourceFileHash: ImageHash,
      sourceImageURL: Option[String]) = {
      val imagePosition: Option[ImagePosition] = xPosition.flatMap { x =>
        yPosition.map(y => ImagePosition(x, y))
      }
      OrganizationLogo(id, createdAt, updatedAt, state, organizationId, imagePosition, width, height, format, kind, imagePath, source, sourceFileHash, sourceImageURL)
    }

    def unapplyToDbRow(logo: OrganizationLogo) = {
      Some((logo.id,
        logo.createdAt,
        logo.updatedAt,
        logo.state,
        logo.organizationId,
        logo.position.map(_.x),
        logo.position.map(_.y),
        logo.width,
        logo.height,
        logo.format,
        logo.kind,
        logo.imagePath,
        logo.source,
        logo.sourceFileHash,
        logo.sourceImageURL))
    }

    def * = (id.?, createdAt, updatedAt, state, organizationId, xPosition, yPosition, width, height, format, kind, imagePath, source, sourceFileHash, sourceImageURL) <> ((applyFromDbRow _).tupled, unapplyToDbRow _)
  }

  def table(tag: Tag) = new OrganizationLogoTable(tag)
  initTable()
}
