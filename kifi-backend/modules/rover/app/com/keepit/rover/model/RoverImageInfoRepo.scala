package com.keepit.rover.model

import com.keepit.common.db.slick.DataBaseComponent
import com.keepit.common.db.slick.DbRepo
import com.keepit.common.db.slick.Repo
import com.keepit.common.logging.Logging
import com.keepit.common.store.{ ImageSize, ImagePath }
import com.keepit.common.time.Clock
import com.keepit.model.ImageProcessState.UploadedImage
import com.keepit.model._
import com.google.inject.{ Singleton, Inject, ImplementedBy }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.healthcheck.AirbrakeNotifier

@ImplementedBy(classOf[RoverImageInfoRepoImpl])
trait RoverImageInfoRepo extends Repo[RoverImageInfo] {
  def getByImageHash(hash: ImageHash)(implicit session: RSession): Set[RoverImageInfo]
  def getByImageHashes(hashes: Set[ImageHash])(implicit session: RSession): Map[ImageHash, Set[RoverImageInfo]]
  def intern(sourceImageHash: ImageHash, imageSource: ImageSource, sourceImageUrl: Option[String], uploadedImage: UploadedImage)(implicit session: RWSession): RoverImageInfo
  def getByImage(sourceImageHash: ImageHash, size: ImageSize, kind: ProcessImageOperation, format: ImageFormat)(implicit session: RSession): Option[RoverImageInfo]
}

@Singleton
class RoverImageInfoRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    airbrake: AirbrakeNotifier) extends DbRepo[RoverImageInfo] with RoverImageInfoRepo with Logging {

  import db.Driver.simple._

  type RepoImpl = RoverImageInfoTable
  class RoverImageInfoTable(tag: Tag) extends RepoTable[RoverImageInfo](db, tag, "rover_image_info") {
    def format = column[ImageFormat]("format", O.NotNull)
    def width = column[Int]("width", O.NotNull)
    def height = column[Int]("height", O.NotNull)
    def kind = column[ProcessImageOperation]("kind", O.NotNull)
    def path = column[ImagePath]("path", O.NotNull)
    def source = column[ImageSource]("source", O.NotNull)
    def sourceImageHash = column[ImageHash]("source_image_hash", O.NotNull)
    def sourceImageUrl = column[Option[String]]("source_image_url", O.Nullable)

    def * = (id.?, createdAt, updatedAt, state, format, width, height, kind, path, source, sourceImageHash, sourceImageUrl) <> ((RoverImageInfo.applyFromDbRow _).tupled, RoverImageInfo.unapplyToDbRow _)
  }

  def table(tag: Tag) = new RoverImageInfoTable(tag)
  initTable()

  override def deleteCache(model: RoverImageInfo)(implicit session: RSession): Unit = {}

  override def invalidateCache(model: RoverImageInfo)(implicit session: RSession): Unit = {}

  def getByImageHash(hash: ImageHash)(implicit session: RSession): Set[RoverImageInfo] = {
    val q = for (r <- rows if r.state === RoverImageInfoStates.ACTIVE && r.sourceImageHash === hash) yield r
    q.list.toSet
  }

  def getByImageHashes(hashes: Set[ImageHash])(implicit session: RSession): Map[ImageHash, Set[RoverImageInfo]] = {
    val q = for (r <- rows if r.state === RoverImageInfoStates.ACTIVE && r.sourceImageHash.inSet(hashes)) yield r
    q.list.toSet.groupBy(_.sourceImageHash)
  }

  def getByImage(sourceImageHash: ImageHash, size: ImageSize, kind: ProcessImageOperation, format: ImageFormat)(implicit session: RSession): Option[RoverImageInfo] = {
    val q = for (r <- rows if r.sourceImageHash === sourceImageHash && r.width === size.width && r.height === size.height && r.kind === kind && r.format === format) yield r
    q.firstOption
  }

  def intern(sourceImageHash: ImageHash, imageSource: ImageSource, sourceImageUrl: Option[String], uploadedImage: UploadedImage)(implicit session: RWSession): RoverImageInfo = {
    val imageSize = ImageSize(uploadedImage.imageInfo.width, uploadedImage.imageInfo.height)
    getByImage(sourceImageHash, imageSize, uploadedImage.processOperation, uploadedImage.format) match {
      case Some(existingInfo) => {
        val updatedInfo = existingInfo.copy(state = RoverImageInfoStates.ACTIVE, sourceImageUrl = sourceImageUrl, path = uploadedImage.key)
        save(updatedInfo)
      }
      case None => {
        val newInfo = RoverImageInfo(
          sourceImageHash = sourceImageHash,
          width = imageSize.width,
          height = imageSize.height,
          kind = uploadedImage.processOperation,
          format = uploadedImage.format,
          path = uploadedImage.key,
          source = imageSource,
          sourceImageUrl = sourceImageUrl
        )
        save(newInfo)
      }
    }
  }

}
