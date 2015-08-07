package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo, Repo }
import com.keepit.common.db.{ Id, State }
import com.keepit.common.store.ImagePath
import com.keepit.common.time.Clock
import org.joda.time.DateTime

@ImplementedBy(classOf[OrganizationAvatarRepoImpl])
trait OrganizationAvatarRepo extends Repo[OrganizationAvatar] {
  def getByOrgId(orgId: Id[Organization], excludeState: Option[State[OrganizationAvatar]] = Some(OrganizationAvatarStates.INACTIVE))(implicit session: RSession): Seq[OrganizationAvatar]
  def getByOrgIds(orgIds: Set[Id[Organization]], excludeState: Option[State[OrganizationAvatar]] = Some(OrganizationAvatarStates.INACTIVE))(implicit session: RSession): Map[Id[Organization], Seq[OrganizationAvatar]]
  def deactivate(model: OrganizationAvatar)(implicit session: RWSession): OrganizationAvatar
}

@Singleton
class OrganizationAvatarRepoImpl @Inject() (
    orgAvatarCache: OrganizationAvatarCache,
    val db: DataBaseComponent,
    val clock: Clock) extends OrganizationAvatarRepo with DbRepo[OrganizationAvatar] {
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

  override def deleteCache(model: OrganizationAvatar)(implicit session: RSession): Unit = {
    orgAvatarCache.remove(OrganizationAvatarKey(model.organizationId))
  }
  override def invalidateCache(model: OrganizationAvatar)(implicit session: RSession): Unit = {
    // Because this cache associates an org id with an entire Seq[OrganizationAvatar], we can't really "save" to it. It's essentially a read-only cache.
    // If the cache becomes invalid, just dump the entire value associated with the Key
    orgAvatarCache.remove(OrganizationAvatarKey(model.organizationId))
  }

  def getByOrgId(orgId: Id[Organization], excludeState: Option[State[OrganizationAvatar]] = Some(OrganizationAvatarStates.INACTIVE))(implicit session: RSession): Seq[OrganizationAvatar] = {
    getByOrgIds(Set(orgId), excludeState).head._2
  }
  def getByOrgIds(orgIds: Set[Id[Organization]], excludeState: Option[State[OrganizationAvatar]] = Some(OrganizationAvatarStates.INACTIVE))(implicit session: RSession): Map[Id[Organization], Seq[OrganizationAvatar]] = {
    orgAvatarCache.bulkGetOrElse(orgIds.map(OrganizationAvatarKey)) { missingKeys =>
      val missingIds = missingKeys.map(_.orgId)
      val q = for (row <- rows if row.organizationId.inSet(missingIds) && row.state =!= excludeState.orNull) yield row
      q.list.groupBy(_.organizationId).map { case (orgId, avatars) => OrganizationAvatarKey(orgId) -> avatars }
    }.map { case (key, orgAvatar) => key.orgId -> orgAvatar }
  }

  def deactivate(model: OrganizationAvatar)(implicit session: RWSession): OrganizationAvatar = {
    save(model.withState(OrganizationAvatarStates.INACTIVE))
  }
}
