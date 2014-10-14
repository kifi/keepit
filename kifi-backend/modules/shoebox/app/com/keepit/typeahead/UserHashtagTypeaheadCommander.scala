package com.keepit.typeahead

import com.keepit.model.{ User, CollectionRepo, Hashtag }
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
import com.keepit.serializer.TupleFormat

case class UserHashtagTypeahead(ownerId: Id[User], tags: Seq[(Hashtag, Int)], filter: PrefixFilter[Hashtag], createdAt: DateTime) extends PersonalTypeahead[User, Hashtag, (Hashtag, Int)] {
  def getInfos(tagIds: Seq[Id[Hashtag]]) = Future.successful(tagIds.map(id => tags(id.id.toInt)))
}

object UserHashtagTypeahead {
  implicit val format = {
    implicit val tupleFormat = TupleFormat.tuple2Format[Hashtag, Int]
    Json.format[UserHashtagTypeahead]
  }
}

class UserHashtagTypeaheadCommander @Inject() (
    val airbrake: AirbrakeNotifier,
    cache: UserHashtagTypeaheadCache,
    clock: Clock,
    collectionRepo: CollectionRepo,
    db: Database) extends Typeahead[User, Hashtag, (Hashtag, Int), UserHashtagTypeahead] {

  protected val refreshRequestConsolidationWindow = 0 seconds

  protected val fetchRequestConsolidationWindow = 15 seconds

  private val refreshIfOlderThan = 15 minutes

  protected def get(userId: Id[User]): Future[Option[UserHashtagTypeahead]] = Future.successful {
    import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
    cache.get(UserHashtagTypeaheadUserIdKey(userId)).map { typeahead =>
      if (typeahead.createdAt.isBefore(clock.now.minusMillis(refreshIfOlderThan.toMillis.toInt))) refresh(userId)
      typeahead
    }
  }

  protected def create(userId: Id[User]): Future[UserHashtagTypeahead] = Future.successful {
    val allTagsWithKeepCount = db.readOnlyMaster { implicit session =>
      collectionRepo.getAllTagsByUserSortedByNumKeeps(userId)
    }.toSeq
    val filter = buildFilter(userId, allTagsWithKeepCount.zipWithIndex.map { case (tagAndKeepCount, idx) => (Id[Hashtag](idx), tagAndKeepCount) })
    UserHashtagTypeahead(userId, allTagsWithKeepCount, filter, clock.now())
  }

  protected def invalidate(typeahead: UserHashtagTypeahead): Unit = {
    import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
    cache.set(UserHashtagTypeaheadUserIdKey(typeahead.ownerId), typeahead)
  }

  protected def extractName(hashtagWithKeepCount: (Hashtag, Int)) = hashtagWithKeepCount._1.tag

  def delete(userId: Id[User]): Unit = {
    import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
    cache.remove(UserHashtagTypeaheadUserIdKey(userId))
  }
}

case class UserHashtagTypeaheadUserIdKey(id: Id[User]) extends Key[UserHashtagTypeahead] {
  override val version = 1
  val namespace = "user_hashtag_typeahead_by_user_id"
  def toKey(): String = id.id.toString
}

class UserHashtagTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserHashtagTypeaheadUserIdKey, UserHashtagTypeahead](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class HashtagHitWithKeepCount(tag: Hashtag, keepCount: Int, matches: Seq[(Int, Int)])
object HashtagHitWithKeepCount {
  implicit val format = {
    implicit val tupleFormat = TupleFormat.tuple2Format[Int, Int]
    Json.format[HashtagHitWithKeepCount]
  }

  def highlight(query: String, tags: Seq[(Hashtag, Int)]): Seq[HashtagHitWithKeepCount] = {
    val queryRegex = PrefixMatching.getHighlightingRegex(query)
    tags.map { case (tag, keepCount) => HashtagHitWithKeepCount(tag, keepCount, PrefixMatching.highlight(tag.tag, queryRegex)) }
  }
}
