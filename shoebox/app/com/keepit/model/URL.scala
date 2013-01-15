package com.keepit.model


import com.keepit.common.db.{CX, Id, Entity, EntityTable, ExternalId, State}
import com.keepit.common.db.NotFoundException
import com.keepit.common.db.StateException
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
  normalizedUriId: Id[NormalizedURI],
  history: Seq[URLHistory] = Seq(),
  state: State[URL] = URLStates.ACTIVE
  ) extends Logging {

  def domain = URI.parse(url).flatMap(_.host)

  def withNormURI(normUriId: Id[NormalizedURI]) = copy(normalizedUriId = normUriId)
  def withHistory(historyItem: URLHistory): URL = copy(history = historyItem +: history)
  def save(implicit conn: Connection): URL = {
    val entity = URLEntity(this.copy(updatedAt = inject[DateTime]))
    assert(1 == entity.save())
    entity.view
  }

  def withState(state: State[URL]) = copy(state = state)

}

object URLCxRepo {

  def all(implicit conn: Connection): Seq[URL] =
    URLEntity.all.map(_.view)

  def get(url: String)(implicit conn: Connection): Option[URL] =
    (URLEntity AS "u").map { u => SELECT (u.*) FROM u WHERE (u.url EQ url) unique }.map(_.view)

  def get(id: Id[URL])(implicit conn: Connection): URL =
    getOpt(id).getOrElse(throw NotFoundException(id))

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
    uri.domain.set(view.domain.map(_.toString))
    uri.normalizedUriId := view.normalizedUriId
    uri.history := {
        val serializer = URLHS.urlHistorySerializer
        Json.stringify(serializer.writes(view.history))
    }
    uri.state := view.state
    uri
  }
}


