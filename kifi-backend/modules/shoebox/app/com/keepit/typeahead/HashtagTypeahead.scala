package com.keepit.typeahead

import com.keepit.model.{ CollectionRepo, Hashtag, Library }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.Id
import scala.concurrent.Future
import com.google.inject.Inject
import play.api.libs.json.Json
import org.joda.time.DateTime
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration._
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.common.json.TupleFormat

case class LibraryHashtagTypeahead(ownerId: Id[Library], tags: Seq[Hashtag], filter: PrefixFilter[Hashtag], createdAt: DateTime) extends PersonalTypeahead[Library, Hashtag, Hashtag] {
  def getInfos(tagIds: Seq[Id[Hashtag]]) = Future.successful(tagIds.map(id => tags(id.id.toInt)))
}

object LibraryHashtagTypeahead {
  implicit val format = Json.format[LibraryHashtagTypeahead]
}

class HashtagTypeahead @Inject() (
    val airbrake: AirbrakeNotifier,
    cache: LibraryHashtagTypeaheadCache,
    clock: Clock,
    collectionRepo: CollectionRepo,
    db: Database) extends Typeahead[Library, Hashtag, Hashtag, LibraryHashtagTypeahead] {

  protected val refreshRequestConsolidationWindow = 0 seconds

  protected val fetchRequestConsolidationWindow = 15 seconds

  private val refreshIfOlderThan = 15 minutes

  protected def get(libraryId: Id[Library]): Future[Option[LibraryHashtagTypeahead]] = Future.successful {
    import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
    cache.get(LibraryHashtagTypeaheadLibraryIdKey(libraryId)).map { typeahead =>
      if (typeahead.createdAt.isBefore(clock.now.minusMillis(refreshIfOlderThan.toMillis.toInt))) refresh(libraryId)
      typeahead
    }
  }

  protected def create(libraryId: Id[Library]): Future[LibraryHashtagTypeahead] = Future.successful {
    val allTags = db.readOnlyMaster { implicit session =>
      collectionRepo.getTagsByLibrary(libraryId)
    }.toSeq.sortBy(_.tag)
    val filter = buildFilter(libraryId, allTags.zipWithIndex.map { case (tag, idx) => (Id[Hashtag](idx), tag) })
    LibraryHashtagTypeahead(libraryId, allTags, filter, clock.now())
  }

  protected def invalidate(typeahead: LibraryHashtagTypeahead): Unit = {
    import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
    cache.set(LibraryHashtagTypeaheadLibraryIdKey(typeahead.ownerId), typeahead)
  }

  protected def extractName(hashtag: Hashtag) = hashtag.tag
}

case class LibraryHashtagTypeaheadLibraryIdKey(id: Id[Library]) extends Key[LibraryHashtagTypeahead] {
  override val version = 1
  val namespace = "library_hashtag_typeahead_by_library_id"
  def toKey(): String = id.id.toString
}

class LibraryHashtagTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LibraryHashtagTypeaheadLibraryIdKey, LibraryHashtagTypeahead](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class HashtagHit(tag: Hashtag, matches: Seq[(Int, Int)])
object HashtagHit {
  implicit val format = {
    implicit val tupleFormat = TupleFormat.tuple2Format[Int, Int]
    Json.format[HashtagHit]
  }
  def highlight(query: String, tags: Seq[Hashtag]): Seq[HashtagHit] = {
    val queryRegex = PrefixMatching.getHighlightingRegex(query)
    tags.map { tag => HashtagHit(tag, PrefixMatching.highlight(tag.tag, queryRegex)) }
  }
}
