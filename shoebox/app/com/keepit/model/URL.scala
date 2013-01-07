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
import java.net.URI
import java.security.MessageDigest
import org.apache.commons.codec.binary.Base64
import scala.collection.mutable
import com.keepit.common.logging.Logging
import com.keepit.common.net.URINormalizer
import com.keepit.serializer.{URLHistorySerializer => URLHS}
import com.keepit.inject._
import play.api.Play.current

case class URLHistoryCause(value: String)
object URLHistoryCause {
  val create = URLHistoryCause("create")
  val split = URLHistoryCause("split")
  val merge = URLHistoryCause("merge")
}
case class URLHistory(date: DateTime, id: Id[NormalizedURI], cause: URLHistoryCause)

object URLHistory {
  def apply(id: Id[NormalizedURI], cause: URLHistoryCause): URLHistory = URLHistory(inject[DateTime], id, cause)
  def apply(id: Id[NormalizedURI]): URLHistory = URLHistory(inject[DateTime], id, URLHistoryCause.create)
}

case class URL (
  id: Option[Id[URL]] = None,
  createdAt: DateTime = inject[DateTime],
  updatedAt: DateTime = inject[DateTime],
  url: String,
  normalizedUriId: Id[NormalizedURI],
  history: Seq[URLHistory] = Seq(),
  state: State[URL] = URL.States.ACTIVE
  ) extends Logging {
  def withHistory(historyItem: URLHistory): URL = copy(history = historyItem +: history)
  def save(implicit conn: Connection): URL = {
    val entity = URLEntity(this.copy(updatedAt = inject[DateTime]))
    assert(1 == entity.save())
    entity.view
  }

  def withState(state: State[URL]) = copy(state = state)

}

object URL {
  def apply(url: String, normalizedUriId: Id[NormalizedURI]): URL =
    apply(url = url, normalizedUriId = normalizedUriId, history = Seq(URLHistory(normalizedUriId)), state = URL.States.ACTIVE)

  def all(implicit conn: Connection): Seq[URL] =
    URLEntity.all.map(_.view)

  def get(url: String)(implicit conn: Connection): Option[URL] =
    (URLEntity AS "u").map { u => SELECT (u.*) FROM u WHERE (u.url EQ url) unique }.map(_.view)

  def get(id: Id[URL])(implicit conn: Connection): URL =
    getOpt(id).getOrElse(throw NotFoundException(id))

  def getOpt(id: Id[URL])(implicit conn: Connection): Option[URL] =
    URLEntity.get(id).map(_.view)

  object States {
    val ACTIVE = State[URL]("active")
    val INACTIVE = State[URL]("inactive")
  }
}

private[model] class URLEntity extends Entity[URL, URLEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(inject[DateTime])
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(inject[DateTime])
  val url = "url".VARCHAR(256).NOT_NULL
  val normalizedUriId = "normalized_uri_id".ID[NormalizedURI].NOT_NULL
  val history = "history".VARCHAR(1024)
  val state = "state".STATE[URL].NOT_NULL(URL.States.ACTIVE)

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
    uri.normalizedUriId := view.normalizedUriId
    uri.history := {
        val serializer = URLHS.urlHistorySerializer
        Json.stringify(serializer.writes(view.history))
    }
    uri.state := view.state
    uri
  }
}


