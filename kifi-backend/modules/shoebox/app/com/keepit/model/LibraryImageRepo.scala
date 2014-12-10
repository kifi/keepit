package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.{ Id, State }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.time.Clock

@ImplementedBy(classOf[LibraryImageRepoImpl])
trait LibraryImageRepo extends Repo[LibraryImage] {
  def getForLibraryId(libraryId: Id[Library], excludeState: Option[State[LibraryImage]] = Some(LibraryImageStates.INACTIVE))(implicit session: RSession): Seq[LibraryImage]
  def getBySourceHash(hash: ImageHash)(implicit session: RSession): Seq[LibraryImage]
}

@Singleton
class LibraryImageRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[LibraryImage] with LibraryImageRepo {

  import db.Driver.simple._

  implicit val LibraryImageSourceMapper = MappedColumnType.base[BaseImageSource, String](_.name, BaseImageSource.apply)
  implicit val imageHashMapper = MappedColumnType.base[ImageHash, String](_.hash, ImageHash.apply)

  type RepoImpl = LibraryImageTable
  class LibraryImageTable(tag: Tag) extends RepoTable[LibraryImage](db, tag, "library_image") {

    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def imagePath = column[String]("image_path", O.NotNull)
    def format = column[ImageFormat]("image_format", O.NotNull)
    def width = column[Int]("width", O.NotNull)
    def height = column[Int]("height", O.NotNull)
    def selectedWidth = column[Int]("selected_width", O.NotNull)
    def selectedHeight = column[Int]("selected_height", O.NotNull)
    def offsetWidth = column[Int]("offset_width", O.NotNull)
    def offsetHeight = column[Int]("offset_height", O.NotNull)
    def source = column[BaseImageSource]("source", O.NotNull)
    def sourceFileHash = column[ImageHash]("source_file_hash", O.NotNull)
    def sourceImageUrl = column[Option[String]]("source_image_url", O.Nullable)
    def isOriginal = column[Boolean]("is_original", O.NotNull)

    def idxLibraryId = index("library_image_f_library_id", libraryId, unique = false)
    def idxSourceFileHashSize = index("library_image_u_source_file_hash_size_library_id", (sourceFileHash, width, height, libraryId), unique = true)

    def * = (id.?, createdAt, updatedAt, state, libraryId, width, height,
      selectedWidth, selectedHeight, offsetWidth, offsetHeight,
      imagePath, format, source, sourceFileHash, sourceImageUrl, isOriginal) <> ((LibraryImage.apply _).tupled, LibraryImage.unapply _)
  }

  def table(tag: Tag) = new LibraryImageTable(tag)
  initTable()

  override def invalidateCache(model: LibraryImage)(implicit session: RSession): Unit = {}

  override def deleteCache(model: LibraryImage)(implicit session: RSession): Unit = {}

  private val getForLibraryIdCompiled = Compiled { libraryId: Column[Id[Library]] =>
    for (r <- rows if r.libraryId === libraryId) yield r
  }
  private val getForLibraryIdAndStatesCompiled = Compiled { (libraryId: Column[Id[Library]], excludeState: Column[State[LibraryImage]]) =>
    for (r <- rows if r.libraryId === libraryId && r.state =!= excludeState) yield r
  }
  def getForLibraryId(libraryId: Id[Library], excludeState: Option[State[LibraryImage]] = Some(LibraryImageStates.INACTIVE))(implicit session: RSession): Seq[LibraryImage] = {
    excludeState match {
      case None =>
        getForLibraryIdCompiled(libraryId).list
      case Some(excludeState) =>
        getForLibraryIdAndStatesCompiled(libraryId, excludeState).list
    }
  }

  private val getBySourceHashCompiled = Compiled { hash: Column[ImageHash] =>
    for (r <- rows if r.sourceFileHash === hash) yield r
  }
  def getBySourceHash(hash: ImageHash)(implicit session: RSession): Seq[LibraryImage] = {
    getBySourceHashCompiled(hash).list
  }

}