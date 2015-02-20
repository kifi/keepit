package com.keepit.model

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.{ States, ModelWithState, State, Id }
import com.keepit.common.logging.AccessLog
import org.joda.time.DateTime
import com.keepit.common.time._
import play.api.libs.json._

import scala.concurrent.duration.Duration

case class LibrarySuggestedSearch(
    id: Option[Id[LibrarySuggestedSearch]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    libraryId: Id[Library],
    term: String,
    weight: Float,
    state: State[LibrarySuggestedSearch] = LibrarySuggestedSearchStates.ACTIVE) extends ModelWithState[LibrarySuggestedSearch] {

  def withId(id: Id[LibrarySuggestedSearch]): LibrarySuggestedSearch = copy(id = Some(id))
  def withUpdateTime(now: DateTime): LibrarySuggestedSearch = copy(updatedAt = now)
  def activateWithWeight(weight: Float): LibrarySuggestedSearch = copy(weight = weight, state = LibrarySuggestedSearchStates.ACTIVE)
}

object LibrarySuggestedSearchStates extends States[LibrarySuggestedSearch]

case class SuggestedSearchTerms(terms: Map[String, Float]) {
  def normalized(): SuggestedSearchTerms = SuggestedSearchTerms.create(this.terms)
  def takeTopK(k: Int): SuggestedSearchTerms = {
    SuggestedSearchTerms(terms.toArray.sortBy(-_._2).take(k).toMap)
  }
}

object SuggestedSearchTerms {
  val MAX_CACHE_LIMIT = 100 // If this goes up, better bump up cache version
  private val MAX_TERM_LEN = 128

  def create(terms: Map[String, Float]): SuggestedSearchTerms = {
    val ts = terms.map { case (word, weight) => (word.trim.toLowerCase.take(MAX_TERM_LEN), weight) }
    SuggestedSearchTerms(ts)
  }

  implicit def format = Json.format[SuggestedSearchTerms]
}

case class LibrarySuggestedSearchKey(id: Id[Library]) extends Key[SuggestedSearchTerms] {
  override val version = 1
  val namespace = "suggested_search_by_libId"
  def toKey(): String = id.id.toString
}

class LibrarySuggestedSearchCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LibrarySuggestedSearchKey, SuggestedSearchTerms](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
