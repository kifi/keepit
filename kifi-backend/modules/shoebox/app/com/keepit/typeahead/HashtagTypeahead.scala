package com.keepit.typeahead

import com.keepit.commanders.{ TagSorting, TagCommander }
import com.keepit.model.{ User, Hashtag }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.Id
import scala.concurrent.{ ExecutionContext, Future }
import com.google.inject.Inject
import play.api.libs.json.Json
import org.joda.time.DateTime
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration._
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.common.json.TupleFormat

case class UserHashtagTypeahead(ownerId: Id[User], tags: Seq[(Hashtag, Int)], filter: PrefixFilter[Hashtag], createdAt: DateTime, updatedAt: DateTime) extends PersonalTypeahead[User, Hashtag, (Hashtag, Int)] {
  def getInfos(tagIds: Seq[Id[Hashtag]]) = Future.successful(tagIds.map(id => tags(id.id.toInt)).filter(_._2 > 0))
}

object UserHashtagTypeahead {
  implicit val format = {
    implicit val tupleFormat = TupleFormat.tuple2Format[Hashtag, Int]
    Json.format[UserHashtagTypeahead]
  }
}

class HashtagTypeahead @Inject() (
    val airbrake: AirbrakeNotifier,
    cache: UserHashtagTypeaheadCache,
    clock: Clock,
    tagCommander: TagCommander,
    db: Database,
    implicit val executionContext: ExecutionContext) extends Typeahead[User, Hashtag, (Hashtag, Int), UserHashtagTypeahead] {

  protected val refreshRequestConsolidationWindow = 5 seconds

  protected val fetchRequestConsolidationWindow = 15 seconds

  private val refreshIfOlderThan = 15 minutes

  protected def get(userId: Id[User]): Future[Option[UserHashtagTypeahead]] = Future.successful {
    import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
    cache.get(UserHashtagTypeaheadUserIdKey(userId)).map { typeahead =>
      if (typeahead.createdAt.isBefore(clock.now.minusMillis(refreshIfOlderThan.toMillis.toInt))) refresh(userId)
      typeahead
    }
  }

  private def makeCanonicalTagsAndCounts(allTagsAndCounts: Seq[(Hashtag, Int)]): Map[String, (Hashtag, Int)] = {
    allTagsAndCounts.groupBy(_._1.normalized).mapValues { tagsAndCounts =>
      val canonicalTag = tagsAndCounts.maxBy(_._2)._1
      val totalCount = tagsAndCounts.map(_._2).sum
      (canonicalTag, totalCount)
    }
  }

  private def sortTagsAndBuildFilter(userId: Id[User], tagsAndCounts: Iterable[(Hashtag, Int)]): (Seq[(Hashtag, Int)], PrefixFilter[Hashtag]) = {
    val sortedTags = tagsAndCounts.toSeq.sortBy { case (_, count) => -count }
    val filter = buildFilter(userId, sortedTags.zipWithIndex.map { case (tagAndKeepCount, idx) => (Id[Hashtag](idx), tagAndKeepCount) })
    (sortedTags, filter)
  }

  protected def create(userId: Id[User]): Future[UserHashtagTypeahead] = Future.successful {
    val allTagsWithKeepCount = db.readOnlyMaster { implicit session =>
      tagCommander.tagsForUser(userId, 0, 2000, TagSorting.NumKeeps).flatMap(c => c.keeps.map(kc => (Hashtag(c.name), kc)))
    }
    val canonicalTagsWithKeepCount = makeCanonicalTagsAndCounts(allTagsWithKeepCount).values
    val (sortedTags, filter) = sortTagsAndBuildFilter(userId, canonicalTagsWithKeepCount)
    val now = clock.now()
    UserHashtagTypeahead(userId, sortedTags, filter, now, now)
  }

  protected def invalidate(typeahead: UserHashtagTypeahead): Unit = {
    import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
    cache.set(UserHashtagTypeaheadUserIdKey(typeahead.ownerId), typeahead)
  }

  def update(userId: Id[User], updates: Seq[(Hashtag, Int)]): Future[Unit] = {
    get(userId).map {
      case None => Future.successful(())
      case Some(typeahead) =>
        val updatesByNormalizedTag = makeCanonicalTagsAndCounts(updates)
        val updatedTags = typeahead.tags.map {
          case (tag, count) =>
            updatesByNormalizedTag.get(tag.normalized) match {
              case Some((canonicalTag, increment)) => (canonicalTag, count + increment)
              case None => (tag, count)
            }
        }
        val newTags = (updatesByNormalizedTag -- typeahead.tags.map(_._1.normalized)).values
        // Actually reordering tags and rebuilding the filter from scratch. If we don't reorder we can append, worth it?
        val (sortedTags, filter) = sortTagsAndBuildFilter(userId, updatedTags ++ newTags)
        val updatedTypeahead = UserHashtagTypeahead(userId, sortedTags, filter, typeahead.createdAt, clock.now())
        invalidate(updatedTypeahead)
    }
  }

  protected def extractName(hashtagWithKeepCount: (Hashtag, Int)) = hashtagWithKeepCount._1.tag
}

case class UserHashtagTypeaheadUserIdKey(id: Id[User]) extends Key[UserHashtagTypeahead] {
  override val version = 2
  val namespace = "user_hashtag_typeahead_by_user_id"
  def toKey(): String = id.id.toString
}

class UserHashtagTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserHashtagTypeaheadUserIdKey, UserHashtagTypeahead](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class HashtagHit(tag: Hashtag, keepCount: Int, matches: Seq[(Int, Int)])
object HashtagHit {
  def highlight(query: String, tags: Seq[(Hashtag, Int)]): Seq[HashtagHit] = {
    val queryRegex = PrefixMatching.getHighlightingRegex(query)
    tags.map { case (tag, keepCount) => HashtagHit(tag, keepCount, PrefixMatching.highlight(tag.tag, queryRegex)) }
  }
}
