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
  def getByOrganizationId(organizationId: Id[Organization], state: State[OrganizationAvatar] = OrganizationAvatarStates.ACTIVE)(implicit s: RSession): Seq[OrganizationAvatar]
  def getByOrganizationIds(organizationIds: Set[Id[Organization]])(implicit session: RSession): Map[Id[Organization], Seq[OrganizationAvatar]]
}

@Singleton
class OrganizationAvatarRepoImpl @Inject() (
    organizationAvatarCache: OrganizationAvatarCache,
    val db: DataBaseComponent,
    val clock: Clock) extends OrganizationAvatarRepo with DbRepo[OrganizationAvatar] {
  override def deleteCache(orgLogo: OrganizationAvatar)(implicit session: RSession) {}
  override def invalidateCache(orgLogo: OrganizationAvatar)(implicit session: RSession) {}

  import db.Driver.simple._

  type RepoImpl = OrganizationAvatarTable
  class OrganizationAvatarTable(tag: Tag) extends RepoTable[OrganizationAvatar](db, tag, "organization_avatar") {
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
      id: Option[Id[OrganizationAvatar]],
      createdAt: DateTime,
      updatedAt: DateTime,
      state: State[OrganizationAvatar],
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
      OrganizationAvatar(id, createdAt, updatedAt, state, organizationId, imagePosition, width, height, format, kind, imagePath, source, sourceFileHash, sourceImageURL)
    }

    def unapplyToDbRow(logo: OrganizationAvatar) = {
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

  def table(tag: Tag) = new OrganizationAvatarTable(tag)
  initTable()

  def getByOrganizationIdCompiled = Compiled { (orgId: Column[Id[Organization]], state: Column[State[OrganizationAvatar]]) =>
    for { row <- rows if row.organizationId === orgId && row.state === state } yield row
  }

  def getByOrganizationId(organizationId: Id[Organization], state: State[OrganizationAvatar] = OrganizationAvatarStates.ACTIVE)(implicit s: RSession): Seq[OrganizationAvatar] = {
    organizationAvatarCache.getOrElse(OrganizationAvatarKey(organizationId)) {
      getByOrganizationIdCompiled(organizationId, state).list
    }
  }

  def getByOrganizationIds(orgIds: Set[Id[Organization]])(implicit session: RSession): Map[Id[Organization], Seq[OrganizationAvatar]] = {
    val keys = orgIds.map(OrganizationAvatarKey)
    organizationAvatarCache.bulkGetOrElse(keys) { missingKeys =>
      val missingOrgIds = missingKeys.map(_.organizationId)
      val missingImages = (for (r <- rows if r.organizationId.inSet(missingOrgIds) && r.state =!= OrganizationAvatarStates.INACTIVE) yield r).list
      missingImages.groupBy(_.organizationId).map { case (orgId, avatars) => OrganizationAvatarKey(orgId) -> avatars }
    } map {
      case (key, images) => key.organizationId -> images
    }
  }
}
