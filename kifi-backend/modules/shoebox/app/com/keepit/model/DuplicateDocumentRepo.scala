package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
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
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[DuplicateDocument](db, "duplicate_document") {
    def uri1Id = column[Id[NormalizedURI]]("uri1_id", O.NotNull)
    def uri2Id = column[Id[NormalizedURI]]("uri2_id", O.NotNull)
    def percentMatch = column[Double]("percent_match", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ uri1Id ~ uri2Id ~ percentMatch ~ state <> (DuplicateDocument, DuplicateDocument.unapply _)
  }

  def getActive(page: Int = 0, size: Int = 50)(implicit session: RSession): Seq[DuplicateDocument] = {
    (for { f <- table if f.state === DuplicateDocumentStates.NEW } yield f).drop(page * size).take(size).list
  }

  def getSimilarTo(id: Id[NormalizedURI])(implicit session: RSession): Seq[DuplicateDocument] = {
    val q = for {
      f <- table if (f.uri1Id === id || f.uri2Id === id)
    } yield f
    q.list
  }
}