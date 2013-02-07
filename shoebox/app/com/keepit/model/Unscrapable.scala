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
import com.keepit.common.logging.Logging
import play.api.libs.json._
import com.google.inject.{Inject, ImplementedBy, Singleton}

case class Unscrapable(
  id: Option[Id[Unscrapable]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  pattern: String,
  state: State[Unscrapable] = UnscrapableStates.ACTIVE
) extends Model[Unscrapable] {

  def withId(id: Id[Unscrapable]) = this.copy(id = Some(id))
  def withState(newState: State[Unscrapable]) = this.copy(state = newState)
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)

}

@ImplementedBy(classOf[UnscrapableRepoImpl])
trait UnscrapableRepo extends Repo[Unscrapable] {
  def allActive()(implicit session: RSession): Seq[Unscrapable]
  def contains(url: String)(implicit session: RSession): Boolean
}

@Singleton
class UnscrapableRepoImpl @Inject() (val db: DataBaseComponent) extends DbRepo[Unscrapable] with UnscrapableRepo {
  import FortyTwoTypeMappers._
  import org.scalaquery.ql._
  import org.scalaquery.ql.ColumnOps._
  import org.scalaquery.ql.basic.BasicProfile
  import org.scalaquery.ql.extended.ExtendedTable
  import db.Driver.Implicit._
  import DBSession._
  import scala.util.matching.Regex

  override lazy val table = new RepoTable[Unscrapable](db, "unscrapable") {
    def pattern = column[String]("pattern", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ pattern ~ state <> (Unscrapable, Unscrapable.unapply _)
  }

  def allActive()(implicit session: RSession): Seq[Unscrapable] =
    (for(f <- table if f.state === UnscrapableStates.ACTIVE) yield f).list

  def contains(url: String)(implicit session: RSession): Boolean = {
    !allActive().forall { s =>
      !url.matches(s.pattern)
    }
  }
}

object UnscrapableStates {
  val ACTIVE = State[Unscrapable]("active")
  val INACTIVE = State[Unscrapable]("inactive")
}
