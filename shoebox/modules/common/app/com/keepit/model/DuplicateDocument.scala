package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import com.keepit.common.crypto._
import com.keepit.inject._
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api._
import play.api.Play.current
import java.net.URI
import java.security.MessageDigest
import scala.collection.mutable
import play.api.libs.json._
import com.google.inject.{Inject, ImplementedBy, Singleton}

case class DuplicateDocument (
  id: Option[Id[DuplicateDocument]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  uri1Id: Id[NormalizedURI],
  uri2Id: Id[NormalizedURI],
  percentMatch: Double,
  state: State[DuplicateDocument] = DuplicateDocumentStates.NEW
) extends Model[DuplicateDocument] {

  assert(uri1Id.id < uri2Id.id, "uri1Id ≥ uri2Id")

  def withId(id: Id[DuplicateDocument]) = this.copy(id = Some(id))
  def withState(newState: State[DuplicateDocument]) = this.copy(state = newState)
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)

}

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

object DuplicateDocumentStates {
  val NEW = State[DuplicateDocument]("new")
  val MERGED = State[DuplicateDocument]("merged")
  val IGNORED = State[DuplicateDocument]("ignored")
  val UNSCRAPABLE = State[DuplicateDocument]("unscrapable")
}
