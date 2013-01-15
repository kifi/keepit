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
import ru.circumflex.orm._
import java.security.MessageDigest
import org.apache.commons.codec.binary.Base64
import scala.collection.mutable
import com.keepit.common.logging.Logging
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
case class URLHistory(date: DateTime, id: Id[NormalizedURI], cause: URLHistoryCause)

object URLHistory {
  def apply(id: Id[NormalizedURI], cause: URLHistoryCause): URLHistory = URLHistory(inject[DateTime], id, cause)
  def apply(id: Id[NormalizedURI]): URLHistory = URLHistory(inject[DateTime], id, URLHistoryCause.CREATE)
}

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

  def withNormURI(normUriId: Id[NormalizedURI]) = copy(normalizedUriId = normUriId)
  def withHistory(historyItem: URLHistory): URL = copy(history = historyItem +: history)

  def save(implicit conn: Connection): URL = {
    val entity = URLEntity(this.copy(updatedAt = inject[DateTime]))
    assert(1 == entity.save())
    entity.view
  }

  def withState(state: State[URL]) = copy(state = state)
}

object URLFactory {
  def apply(url: String, normalizedUriId: Id[NormalizedURI]) =
    URL(url = url, normalizedUriId = normalizedUriId, domain = URI.parse(url).map(_.host.toString))
}


@ImplementedBy(classOf[URLRepoImpl])
trait URLRepo extends Repo[URL] {
  def get(url: String)(implicit session: RSession): Option[URL]
  def getByDomain(domain: String)(implicit session: RSession): List[URL]
}

@Singleton
class URLRepoImpl @Inject() (val db: DataBaseComponent) extends DbRepo[URL] with URLRepo {
  import FortyTwoTypeMappers._
  import org.scalaquery.ql._
  import org.scalaquery.ql.ColumnOps._
  import org.scalaquery.ql.basic.BasicProfile
  import org.scalaquery.ql.extended.ExtendedTable
  import db.Driver.Implicit._
  import DBSession._

  override lazy val table = new RepoTable[URL](db, "follow") {
    def url = column[String]("url", O.NotNull)
    def domain = column[String]("domain", O.Nullable)
    def normalizedUriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def history = column[Seq[URLHistory]]("history", O.NotNull)
    def state = column[State[URL]]("state", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ url ~ domain.? ~ normalizedUriId ~ history ~ state <> (URL, URL.unapply _)
  }

  def get(url: String)(implicit session: RSession): Option[URL] =
    (for(u <- table if u.url === url && u.state === URLStates.ACTIVE) yield u).firstOption

  def getByDomain(domain: String)(implicit session: RSession): List[URL] =
    (for(u <- table if u.domain === domain && u.state === URLStates.ACTIVE) yield u).list
}

//slicked
object URLCxRepo {

  //slicked
  def all(implicit conn: Connection): Seq[URL] =
    URLEntity.all.map(_.view)

  def get(url: String)(implicit conn: Connection): Option[URL] =
    (URLEntity AS "u").map { u => SELECT (u.*) FROM u WHERE (u.url EQ url) unique }.map(_.view)

  //slicked
  def get(id: Id[URL])(implicit conn: Connection): URL =
    getOpt(id).getOrElse(throw NotFoundException(id))

  //WTF?
  def getOpt(id: Id[URL])(implicit conn: Connection): Option[URL] =
    URLEntity.get(id).map(_.view)

  def getByDomain(domain: String)(implicit conn: Connection) =
    (URLEntity AS "n").map { n => SELECT (n.*) FROM n WHERE (n.domain EQ domain) }.list.map( _.view )
}

object URLStates {
  val ACTIVE = State[URL]("active")
  val INACTIVE = State[URL]("inactive")
}

private[model] class URLEntity extends Entity[URL, URLEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(inject[DateTime])
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(inject[DateTime])
  val url = "url".VARCHAR(2048).NOT_NULL
  val normalizedUriId = "normalized_uri_id".ID[NormalizedURI].NOT_NULL
  val history = "history".VARCHAR(2048)
  val state = "state".STATE[URL].NOT_NULL(URLStates.ACTIVE)
  val domain = "domain".VARCHAR(512)

  def relation = URLEntity

  def view(implicit conn: Connection): URL = URL(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    url = url(),
    domain = domain.value,
    normalizedUriId = normalizedUriId(),
    history = {
      try {
        val json = Json.parse(history.value.getOrElse("{}")) // after grandfathering, force having a value
        val serializer = URLHS.urlHistorySerializer
        serializer.reads(json)
      }
      catch {
        case ex: Throwable =>
          // after grandfathering process, throw error
          Seq[URLHistory]()
      }
    },
    state = state()
  )
}

private[model] object URLEntity extends URLEntity with EntityTable[URL, URLEntity] {
  override def relationName = "url"

  def apply(view: URL): URLEntity = {
    val uri = new URLEntity
    uri.id.set(view.id)
    uri.createdAt := view.createdAt
    uri.updatedAt := view.updatedAt
    uri.url := view.url
    uri.domain.set(view.domain)
    uri.normalizedUriId := view.normalizedUriId
    uri.history := {
        val serializer = URLHS.urlHistorySerializer
        Json.stringify(serializer.writes(view.history))
    }
    uri.state := view.state
    uri
  }
}
