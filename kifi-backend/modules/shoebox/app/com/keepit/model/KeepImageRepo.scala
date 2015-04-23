package com.keepit.model
import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo, Repo }
import com.keepit.common.time.Clock

@ImplementedBy(classOf[KeepImageRepoImpl])
trait KeepImageRepo extends Repo[KeepImage] {
  def getForKeepId(keepId: Id[Keep])(implicit session: RSession): Seq[KeepImage]
  def getAllForKeepId(keepId: Id[Keep])(implicit session: RSession): Seq[KeepImage]
  def getAllForKeepIds(keepIds: Set[Id[Keep]])(implicit session: RSession): Seq[KeepImage]
  def getBySourceHash(hash: ImageHash)(implicit session: RSession): Seq[KeepImage]
}

@Singleton
class KeepImageRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[KeepImage] with KeepImageRepo {

  import db.Driver.simple._

  type RepoImpl = KeepImageTable
  class KeepImageTable(tag: Tag) extends RepoTable[KeepImage](db, tag, "keep_image") {

    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def imagePath = column[String]("image_path", O.NotNull)
    def format = column[ImageFormat]("image_format", O.NotNull)
    def width = column[Int]("width", O.NotNull)
    def height = column[Int]("height", O.NotNull)
    def source = column[ImageSource]("source", O.NotNull)
    def sourceFileHash = column[ImageHash]("source_file_hash", O.NotNull)
    def sourceImageUrl = column[Option[String]]("source_image_url", O.Nullable)
    def isOriginal = column[Boolean]("is_original", O.NotNull)
    def kind = column[ProcessImageOperation]("kind", O.NotNull)

    def idxKeepId = index("keep_image_f_keep_id", keepId, unique = false)
    def idxSourceFileHashSize = index("keep_image_u_source_file_hash_size_keep_id", (sourceFileHash, width, height, keepId), unique = true)

    def * = (id.?, createdAt, updatedAt, state, keepId, imagePath, format, width, height, source, sourceFileHash, sourceImageUrl, isOriginal, kind) <> ((KeepImage.apply _).tupled, KeepImage.unapply _)
  }

  def table(tag: Tag) = new KeepImageTable(tag)
  initTable()

  override def invalidateCache(model: KeepImage)(implicit session: RSession): Unit = {}

  override def deleteCache(model: KeepImage)(implicit session: RSession): Unit = {}

  private val getForKeepIdCompiled = Compiled { keepId: Column[Id[Keep]] =>
    for (r <- rows if r.keepId === keepId && r.state === KeepImageStates.ACTIVE) yield r
  }
  def getForKeepId(keepId: Id[Keep])(implicit session: RSession): Seq[KeepImage] = {
    getForKeepIdCompiled(keepId).list
  }

  private val getAllForKeepIdCompiled = Compiled { keepId: Column[Id[Keep]] =>
    for (r <- rows if r.keepId === keepId) yield r
  }
  def getAllForKeepId(keepId: Id[Keep])(implicit session: RSession): Seq[KeepImage] = {
    getAllForKeepIdCompiled(keepId).list
  }

  def getAllForKeepIds(keepIds: Set[Id[Keep]])(implicit session: RSession): Seq[KeepImage] = {
    if (keepIds.nonEmpty) {
      (for (r <- rows if r.keepId.inSet(keepIds)) yield r).list
    } else {
      Seq.empty
    }
  }

  private val getBySourceHashCompiled = Compiled { hash: Column[ImageHash] =>
    for (r <- rows if r.sourceFileHash === hash) yield r
  }
  def getBySourceHash(hash: ImageHash)(implicit session: RSession): Seq[KeepImage] = {
    getBySourceHashCompiled(hash).list
  }

}

