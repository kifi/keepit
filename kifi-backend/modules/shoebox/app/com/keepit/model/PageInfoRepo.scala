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

@ImplementedBy(classOf[PageInfoRepoImpl])
trait PageInfoRepo extends Repo[PageInfo] with SeqNumberFunction[PageInfo] {
  def getByUri(uriId:Id[NormalizedURI])(implicit ro:RSession):Option[PageInfo]
}

@Singleton
class PageInfoRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  airbrake: AirbrakeNotifier)
extends DbRepo[PageInfo] with PageInfoRepo with SeqNumberDbFunction[PageInfo] with Logging {

  import db.Driver.simple._

  private val sequence = db.getSequence[PageInfo]("page_info_sequence")

  type RepoImpl = PageInfoTable
  class PageInfoTable(tag: Tag) extends RepoTable[PageInfo](db, tag, "page_info") with SeqNumberColumn[PageInfo] {
    def uriId           = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def description     = column[String]("description")
    def safe            = column[Boolean]("safe")
    def faviconUrl      = column[String]("favicon_url")
    def imageInfoId     = column[Id[ImageInfo]]("image_info_id")
    def * = (id.?,createdAt,updatedAt,state,seq,uriId,description.?,safe.?,faviconUrl.?,imageInfoId.?) <> ((PageInfo.apply _).tupled, PageInfo.unapply _)
  }

  def table(tag:Tag) = new PageInfoTable(tag)
  initTable()

  override def deleteCache(model: PageInfo)(implicit session: RSession):Unit = {}
  override def invalidateCache(model: PageInfo)(implicit session: RSession):Unit = {}

  override def save(model: PageInfo)(implicit session: RWSession): PageInfo = {
    val toSave = model.copy(seq = sequence.incrementAndGet())
    log.info(s"[PageInfoRepo.save] $toSave")
    super.save(toSave)
  }

  override def getByUri(uriId: Id[NormalizedURI])(implicit ro: RSession): Option[PageInfo] = {
    (for(f <- rows if f.uriId === uriId && f.state === PageInfoStates.ACTIVE) yield f).firstOption
  }
}
