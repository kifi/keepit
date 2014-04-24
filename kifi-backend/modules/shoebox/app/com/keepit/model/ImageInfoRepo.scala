package com.keepit.model


import com.google.inject.{Provider, Inject, Singleton, ImplementedBy}
import com.google.inject.Inject
import com.keepit.common.time.Clock
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.store.ImageSize

@ImplementedBy(classOf[ImageInfoRepoImpl])
trait ImageInfoRepo extends Repo[ImageInfo] with SeqNumberFunction[ImageInfo] {
  def getByUri(id:Id[NormalizedURI])(implicit ro:RSession):Seq[ImageInfo]
  def getByUrl(url:String)(implicit ro:RSession):Option[ImageInfo]
  def getByUriWithSize(id:Id[NormalizedURI], minSize: ImageSize)(implicit ro:RSession):List[ImageInfo]
}

@Singleton
class ImageInfoRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  airbrake: AirbrakeNotifier)
  extends DbRepo[ImageInfo] with ImageInfoRepo with SeqNumberDbFunction[ImageInfo] with Logging {

  import db.Driver.simple._

  private val sequence = db.getSequence[ImageInfo]("image_info_sequence")

  type RepoImpl = ImageInfoTable
  class ImageInfoTable(tag: Tag) extends RepoTable[ImageInfo](db, tag, "image_info") with ExternalIdColumn[ImageInfo] with SeqNumberColumn[ImageInfo] {
    def uriId    = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def url      = column[String]("url", O.NotNull)
    def name     = column[String]("name")
    def caption  = column[String]("caption")
    def width    = column[Int]("width")
    def height   = column[Int]("height")
    def sz       = column[Int]("sz")
    def provider = column[ImageProvider]("provider", O.NotNull)
    def format   = column[ImageFormat]("format", O.NotNull)
    def priority = column[Int]("priority")
    def * = (id.?,createdAt,updatedAt,state,seq,externalId,uriId,url,name.?,caption.?,width.?,height.?,sz.?,provider,format.?,priority.?) <> ((ImageInfo.apply _).tupled, ImageInfo.unapply)
  }

  def table(tag:Tag) = new ImageInfoTable(tag)
  initTable()

  override def deleteCache(model: ImageInfo)(implicit session: RSession):Unit = {}
  override def invalidateCache(model: ImageInfo)(implicit session: RSession):Unit = {}

  override def save(model: ImageInfo)(implicit session: RWSession): ImageInfo = {
    val toSave = model.copy(seq = sequence.incrementAndGet())
    log.info(s"[ImageRepo.save] $toSave")
    super.save(toSave)
  }


  def getByUri(id: Id[NormalizedURI])(implicit ro: RSession): Seq[ImageInfo] = {
    (for(f <- rows if f.uriId === id && f.state === ImageInfoStates.ACTIVE) yield f).list()
  }

  def getByUrl(url:String)(implicit ro: RSession): Option[ImageInfo] = {
    (for(f <- rows if f.url === url && f.state === ImageInfoStates.ACTIVE) yield f).firstOption
  }

  def getByUriWithSize(id:Id[NormalizedURI], minSize: ImageSize)(implicit ro:RSession):List[ImageInfo] = {
    (for(f <- rows if f.uriId === id && f.state === ImageInfoStates.ACTIVE &&
      f.width < minSize.width && f.height < minSize.height) yield f).list()
  }
}
