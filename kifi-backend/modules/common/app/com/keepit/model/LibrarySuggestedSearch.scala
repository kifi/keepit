package com.keepit.model

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.{ States, ModelWithState, State, Id }
import com.keepit.common.logging.AccessLog
import com.keepit.common.queue.messages.SuggestedSearchTerms
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
    state: State[LibrarySuggestedSearch] = LibrarySuggestedSearchStates.ACTIVE,
    termKind: SuggestedSearchTermKind) extends ModelWithState[LibrarySuggestedSearch] {

  def withId(id: Id[LibrarySuggestedSearch]): LibrarySuggestedSearch = copy(id = Some(id))
  def withUpdateTime(now: DateTime): LibrarySuggestedSearch = copy(updatedAt = now)
  def activateWithWeight(weight: Float): LibrarySuggestedSearch = copy(weight = weight, state = LibrarySuggestedSearchStates.ACTIVE)
}

object LibrarySuggestedSearchStates extends States[LibrarySuggestedSearch]

case class SuggestedSearchTermKind(value: String)

object SuggestedSearchTermKind {
  val AUTO = SuggestedSearchTermKind("auto")
  val HASHTAG = SuggestedSearchTermKind("hashtag")
}

case class LibrarySuggestedSearchKey(id: Id[Library], kind: SuggestedSearchTermKind) extends Key[SuggestedSearchTerms] {
  override val version = 2
  val namespace = "suggested_search_by_libId"
  def toKey(): String = id.id.toString + "#" + kind.value
}

class LibrarySuggestedSearchCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LibrarySuggestedSearchKey, SuggestedSearchTerms](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
