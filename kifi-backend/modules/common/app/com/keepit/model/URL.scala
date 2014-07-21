package com.keepit.model

import org.joda.time.DateTime

import com.keepit.common.db._
import com.keepit.common.net.URI
import com.keepit.common.time._

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class URLHistoryCause(value: String)
object URLHistoryCause {
  val CREATE = URLHistoryCause("create")
  val MIGRATED = URLHistoryCause("migrated")
}
case class URLHistory(date: DateTime, id: Id[NormalizedURI], cause: URLHistoryCause = URLHistoryCause.CREATE)

object URLHistory {
  implicit val format = (
    (__ \ 'date).format(DateTimeJsonFormat) and
    (__ \ 'id).format(Id.format[NormalizedURI]) and
    (__ \ 'cause).format[String].inmap(URLHistoryCause.apply, unlift(URLHistoryCause.unapply))
  )(URLHistory.apply _, unlift(URLHistory.unapply))

  val LENGTH_LIMIT = 3 // only last 3 history records to prevent DB column overflow
}

case class URL(
    id: Option[Id[URL]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    url: String,
    domain: Option[String],
    normalizedUriId: Id[NormalizedURI],
    history: Seq[URLHistory] = Seq(),
    state: State[URL] = URLStates.ACTIVE) extends ModelWithState[URL] {
  def withId(id: Id[URL]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withNormUriId(normUriId: Id[NormalizedURI]) = copy(normalizedUriId = normUriId)
  def withHistory(historyItem: URLHistory): URL = copy(history = (historyItem +: history).take(URLHistory.LENGTH_LIMIT))

  def withState(state: State[URL]) = copy(state = state)
}

object URLFactory {
  val MAX_URL_SIZE = 3072
  def apply(url: String, normalizedUriId: Id[NormalizedURI]) = {
    if (url.size > MAX_URL_SIZE) throw new Exception(s"url size is ${url.size} which exceeds $MAX_URL_SIZE: $url")
    URL(url = url, normalizedUriId = normalizedUriId, domain = URI.parse(url).toOption.flatMap(_.host).map(_.name))
  }
}

object URLStates extends States[URL] {
  val MERGED = State[URL]("merged")
}
