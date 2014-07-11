package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.time.Clock

@ImplementedBy(classOf[DuplicateDocumentRepoImpl])
trait DuplicateDocumentRepo extends Repo[DuplicateDocument] {
  def getSimilarTo(id: Id[NormalizedURI])(implicit session: RSession): Seq[DuplicateDocument]
  def getActive(page: Int = 0, size: Int = 50)(implicit session: RSession): Seq[DuplicateDocument]
}

@Singleton
class DuplicateDocumentRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[DuplicateDocument] with DuplicateDocumentRepo {
  import db.Driver.simple._

  type RepoImpl = DuplicateDocumentTable
  class DuplicateDocumentTable(tag: Tag) extends RepoTable[DuplicateDocument](db, tag, "duplicate_document") {
    def uri1Id = column[Id[NormalizedURI]]("uri1_id", O.NotNull)
    def uri2Id = column[Id[NormalizedURI]]("uri2_id", O.NotNull)
    def percentMatch = column[Double]("percent_match", O.NotNull)
    def * = (id.?, createdAt, updatedAt, uri1Id, uri2Id, percentMatch, state) <> ((DuplicateDocument.apply _).tupled, DuplicateDocument.unapply _)
  }

  def table(tag: Tag) = new DuplicateDocumentTable(tag)
  initTable()

  override def invalidateCache(model: DuplicateDocument)(implicit session: RSession): Unit = {}
  override def deleteCache(model: DuplicateDocument)(implicit session: RSession): Unit = {}

  def getActive(page: Int = 0, size: Int = 50)(implicit session: RSession): Seq[DuplicateDocument] = {
    (for { f <- rows if f.state === DuplicateDocumentStates.NEW } yield f).drop(page * size).take(size).list
  }

  def getSimilarTo(id: Id[NormalizedURI])(implicit session: RSession): Seq[DuplicateDocument] = {
    val q = for {
      f <- rows if (f.uri1Id === id || f.uri2Id === id)
    } yield f
    q.list
  }
}