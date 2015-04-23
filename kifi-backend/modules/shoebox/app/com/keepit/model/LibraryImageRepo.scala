package com.keepit.model

import com.keepit.common.core._

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.{ Id, State }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.time.Clock

@ImplementedBy(classOf[LibraryImageRepoImpl])
trait LibraryImageRepo extends Repo[LibraryImage] {
  def getActiveForLibraryId(libraryId: Id[Library])(implicit session: RSession): Seq[LibraryImage]
  def getActiveForLibraryIds(libraryIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Seq[LibraryImage]]
  def getAllForLibraryId(libraryId: Id[Library])(implicit session: RSession): Seq[LibraryImage]
}

@Singleton
class LibraryImageRepoImpl @Inject() (
    libraryImageCache: LibraryImageCache,
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[LibraryImage] with LibraryImageRepo {

  import db.Driver.simple._

  type RepoImpl = LibraryImageTable
  class LibraryImageTable(tag: Tag) extends RepoTable[LibraryImage](db, tag, "library_image") {

    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def imagePath = column[String]("image_path", O.NotNull)
    def format = column[ImageFormat]("image_format", O.NotNull)
    def width = column[Int]("width", O.NotNull)
    def height = column[Int]("height", O.NotNull)
    def positionX = column[Int]("position_x", O.Nullable)
    def positionY = column[Int]("position_y", O.Nullable)
    def source = column[ImageSource]("source", O.NotNull)
    def sourceFileHash = column[ImageHash]("source_file_hash", O.NotNull)
    def isOriginal = column[Boolean]("is_original", O.NotNull)

    def idxLibraryId = index("library_image_f_library_id", libraryId, unique = false)
    def idxSourceFileHashSize = index("library_image_u_source_file_hash_size_library_id", (sourceFileHash, width, height, libraryId), unique = true)

    def * = (id.?, createdAt, updatedAt, state, libraryId, width, height, positionX.?, positionY.?,
      imagePath, format, source, sourceFileHash, isOriginal) <> ((LibraryImage.apply _).tupled, LibraryImage.unapply _)
  }

  def table(tag: Tag) = new LibraryImageTable(tag)
  initTable()

  override def invalidateCache(model: LibraryImage)(implicit session: RSession): Unit = {
    libraryImageCache.remove(LibraryImageKey(model.libraryId))
  }

  override def deleteCache(model: LibraryImage)(implicit session: RSession): Unit = {
    libraryImageCache.remove(LibraryImageKey(model.libraryId))
  }

  private val getAllForLibraryIdCompiled = Compiled { libraryId: Column[Id[Library]] =>
    for (r <- rows if r.libraryId === libraryId) yield r
  }

  private val getActiveForLibraryIdAndStatesCompiled = Compiled { (libraryId: Column[Id[Library]]) =>
    for (r <- rows if r.libraryId === libraryId && r.state =!= LibraryImageStates.INACTIVE) yield r
  }

  def getActiveForLibraryId(libraryId: Id[Library])(implicit session: RSession): Seq[LibraryImage] = {
    libraryImageCache.getOrElse(LibraryImageKey(libraryId)) {
      getActiveForLibraryIdAndStatesCompiled(libraryId).list
    }
  }

  def getActiveForLibraryIds(libraryIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Seq[LibraryImage]] = {
    val keys = libraryIds.map(LibraryImageKey(_))
    libraryImageCache.bulkGetOrElse(keys) { missingKeys =>
      val missingLibraryIds = missingKeys.map(_.libraryId)
      val missingImages = (for (r <- rows if r.libraryId.inSet(missingLibraryIds) && r.state =!= LibraryImageStates.INACTIVE) yield r).list
      missingImages.groupBy(_.libraryId).map { case (libraryId, images) => LibraryImageKey(libraryId) -> images }
    } map {
      case (key, images) => key.libraryId -> images
    }
  }

  def getAllForLibraryId(libraryId: Id[Library])(implicit session: RSession): Seq[LibraryImage] = {
    getAllForLibraryIdCompiled(libraryId).list
  }

}
