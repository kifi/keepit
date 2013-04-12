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
import com.keepit.common.cache._
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.serializer.UnscrapableSerializer
import scala.concurrent.duration._

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

case class UnscrapableAllKey() extends Key[List[Unscrapable]] {
  val namespace = "unscrapable_all"
  def toKey(): String = "all"
}
class UnscrapableAllCache @Inject() (val repo: FortyTwoCachePlugin) extends FortyTwoCache[UnscrapableAllKey, List[Unscrapable]] {
  val ttl = 0 seconds
  def deserialize(obj: Any): List[Unscrapable] = UnscrapableSerializer.unscrapableSerializer.readsSeq(Json.parse(obj.asInstanceOf[String]).asInstanceOf[JsArray])
  def serialize(unscrapable: List[Unscrapable]) = UnscrapableSerializer.unscrapableSerializer.writesSeq(unscrapable)
}


@Singleton
class UnscrapableRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val unscrapableCache: UnscrapableAllCache)
    extends DbRepo[Unscrapable] with UnscrapableRepo {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._
  import scala.util.matching.Regex

  override lazy val table = new RepoTable[Unscrapable](db, "unscrapable") {
    def pattern = column[String]("pattern", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ pattern ~ state <> (Unscrapable, Unscrapable.unapply _)
  }

  private var allMemCache: Option[Seq[Unscrapable]] = None

  override def invalidateCache(unscrapable: Unscrapable)(implicit session: RSession) = {
    unscrapableCache.remove(UnscrapableAllKey())
    allMemCache = None
    unscrapable
  }

  def allActive()(implicit session: RSession): Seq[Unscrapable] =
    allMemCache.getOrElse {
      val result = unscrapableCache.getOrElse(UnscrapableAllKey()) {
        (for(f <- table if f.state === UnscrapableStates.ACTIVE) yield f).list
      }
      allMemCache = Some(result)
      result
    }

  def contains(url: String)(implicit session: RSession): Boolean = {
    !allActive().forall { s =>
      !url.matches(s.pattern)
    }
  }
}

object UnscrapableStates extends States[Unscrapable]
