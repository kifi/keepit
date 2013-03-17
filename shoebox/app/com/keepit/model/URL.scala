package com.keepit.model


import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import com.keepit.common.crypto._
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api._
import play.api.libs.json._
import java.security.MessageDigest
import org.apache.commons.codec.binary.Base64
import scala.collection.mutable
import com.keepit.common.net.{URI, URINormalizer}
import com.keepit.serializer.{URLHistorySerializer => URLHS}
import com.keepit.inject._
import com.google.inject.{Inject, ImplementedBy, Singleton}
import play.api.Play.current

case class URLHistoryCause(value: String)
object URLHistoryCause {
  val CREATE = URLHistoryCause("create")
  val SPLIT = URLHistoryCause("split")
  val MERGE = URLHistoryCause("merge")
}
case class URLHistory(date: DateTime, id: Id[NormalizedURI], cause: URLHistoryCause = URLHistoryCause.CREATE)

case class URL (
  id: Option[Id[URL]] = None,
  createdAt: DateTime = inject[DateTime],
  updatedAt: DateTime = inject[DateTime],
  url: String,
  domain: Option[String],
  normalizedUriId: Id[NormalizedURI],
  history: Seq[URLHistory] = Seq(),
  state: State[URL] = URLStates.ACTIVE
) extends Model[URL] {
  def withId(id: Id[URL]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withNormUriId(normUriId: Id[NormalizedURI]) = copy(normalizedUriId = normUriId)
  def withHistory(historyItem: URLHistory): URL = copy(history = historyItem +: history)
  def withState(state: State[URL]) = copy(state = state)
}

object URLFactory {
  def apply(url: String, normalizedUriId: Id[NormalizedURI],
      createdAt: DateTime = inject[DateTime], updatedAt: DateTime = inject[DateTime]) =
    URL(url = url, normalizedUriId = normalizedUriId, domain = URI.parse(url).toOption.flatMap(_.host).map(_.name),
      createdAt = createdAt, updatedAt = updatedAt)
}

@ImplementedBy(classOf[URLRepoImpl])
trait URLRepo extends Repo[URL] {
  def get(url: String)(implicit session: RSession): Option[URL]
  def getByDomain(domain: String)(implicit session: RSession): List[URL]
  def getByNormUri(normalizedUriId: Id[NormalizedURI])(implicit session: RSession): Seq[URL]
}

@Singleton
class URLRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[URL] with URLRepo {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override lazy val table = new RepoTable[URL](db, "url") {
    def url = column[String]("url", O.NotNull)
    def domain = column[String]("domain", O.Nullable)
    def normalizedUriId = column[Id[NormalizedURI]]("normalized_uri_id", O.NotNull)
    def history = column[Seq[URLHistory]]("history", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ url ~ domain.? ~ normalizedUriId ~ history ~ state <> (URL, URL.unapply _)
  }

  def get(url: String)(implicit session: RSession): Option[URL] =
    (for(u <- table if u.url === url && u.state === URLStates.ACTIVE) yield u).firstOption

  def getByDomain(domain: String)(implicit session: RSession): List[URL] =
    (for(u <- table if u.domain === domain && u.state === URLStates.ACTIVE) yield u).list

  def getByNormUri(normalizedUriId: Id[NormalizedURI])(implicit session: RSession): Seq[URL] =
    (for(u <- table if u.normalizedUriId === normalizedUriId && u.state === URLStates.ACTIVE) yield u).list
}

object URLStates extends States[URL] {
  val MERGED = State[URL]("merged")
}
