package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.time.Clock

@ImplementedBy(classOf[URIImageRepoImpl])
trait URIImageRepo extends Repo[URIImage] with ExternalIdColumnFunction[URIImage] {
  def getByExtId(extId: ExternalId[URIImage])(implicit session: RSession): Option[URIImage]
}

@Singleton
class URIImageRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[URIImage] with URIImageRepo with ExternalIdColumnDbFunction[URIImage] {
  import db.Driver.simple._

  type RepoImpl = URIImageTable
  class URIImageTable(tag: Tag) extends RepoTable[URIImage](db, tag, "uri_image") with ExternalIdColumn[URIImage] with NamedColumns{
    def width = column[Int]("width", O.NotNull)
    def height = column[Int]("height", O.NotNull)
    def source = column[URIImageSource]("source", O.NotNull)
    def format = column[URIImageFormat]("format", O.NotNull)
    def sourceUrl = column[String]("source_url", O.NotNull)
    def * = (id.?, createdAt, updatedAt, externalId, width, height, source, format, sourceUrl) <> ((URIImage.apply _).tupled, URIImage.unapply _)
  }

  def table(tag: Tag) = new URIImageTable(tag)
  initTable()
  
  def getByExtId(extId: ExternalId[URIImage])(implicit session: RSession): Option[URIImage] =
    (for(b <- rows if b.externalId === extId) yield b).firstOption

  override def deleteCache(model: URIImage)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: URIImage)(implicit session: RSession): Unit = {}
}
