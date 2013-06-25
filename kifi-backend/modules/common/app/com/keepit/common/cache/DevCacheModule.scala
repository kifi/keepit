package com.keepit.common.cache

import com.google.inject.{Provides, Singleton}
import scala.concurrent.duration._
import com.keepit.common.social.{CommentWithBasicUserCache, BasicUserUserIdCache}
import net.codingwell.scalaguice.ScalaModule
import com.keepit.model.BookmarkUriUserCache
import com.keepit.model.UserCollectionsCache
import com.keepit.model.CollectionsForBookmarkCache
import com.keepit.model.NormalizedURICache
import com.keepit.model.NormalizedURIUrlHashCache
import com.keepit.model.SocialUserInfoUserCache
import com.keepit.model.SocialUserInfoNetworkCache
import com.keepit.model.UnscrapableAllCache
import com.keepit.model.UserExternalIdCache
import com.keepit.model.UserIdCache
import com.keepit.model.UserSessionExternalIdCache
import com.keepit.model.ExternalUserIdCache
import com.keepit.model.UserExperimentCache
import com.keepit.model.SliderHistoryUserIdCache
import com.keepit.model.BookmarkCountCache
import com.keepit.model.CommentCountUriIdCache
import com.keepit.model.UserValueCache
import com.keepit.model.BrowsingHistoryUserIdCache
import com.keepit.model.ClickHistoryUserIdCache
import com.keepit.search.ActiveExperimentsCache
import com.keepit.model.UserConnectionIdCache
import scala.collection.concurrent.{TrieMap => ConcurrentMap}

class DevCacheModule extends ScalaModule {

  def configure {
    bind[FortyTwoCachePlugin].to[HashMapMemoryCache]
    bind[InMemoryCachePlugin].to[HashMapMemoryCache]
  }

  @Singleton
  @Provides
  def basicUserUserIdCache(outerRepo: FortyTwoCachePlugin) =
    new BasicUserUserIdCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def commentWithBasicUserCache(outerRepo: FortyTwoCachePlugin) =
    new CommentWithBasicUserCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def bookmarkUriUserCache(outerRepo: FortyTwoCachePlugin) =
    new BookmarkUriUserCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def userCollectionCache(outerRepo: FortyTwoCachePlugin) =
    new UserCollectionsCache((outerRepo, 1 day))

  @Singleton
  @Provides
  def collectionsForBookmarkCache(outerRepo: FortyTwoCachePlugin) =
    new CollectionsForBookmarkCache((outerRepo, 1 day))

  @Singleton
  @Provides
  def normalizedURICache(outerRepo: FortyTwoCachePlugin) =
    new NormalizedURICache((outerRepo, 7 days))

  @Singleton
  @Provides
  def normalizedURIUrlHashCache(outerRepo: FortyTwoCachePlugin) =
    new NormalizedURIUrlHashCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def socialUserInfoUserCache(outerRepo: FortyTwoCachePlugin) =
    new SocialUserInfoUserCache((outerRepo, 30 days))

  @Singleton
  @Provides
  def socialUserInfoNetworkCache(outerRepo: FortyTwoCachePlugin) =
    new SocialUserInfoNetworkCache((outerRepo, 30 days))

  @Singleton
  @Provides
  def unscrapableAllCache(outerRepo: FortyTwoCachePlugin) =
    new UnscrapableAllCache((outerRepo, 0 second))

  @Singleton
  @Provides
  def userExternalIdCache(outerRepo: FortyTwoCachePlugin) =
    new UserExternalIdCache((outerRepo, 24 hours))

  @Singleton
  @Provides
  def userIdCache(outerRepo: FortyTwoCachePlugin) =
    new UserIdCache((outerRepo, 24 hours))

  @Singleton
  @Provides
  def userSessionExternalIdCache(outerRepo: FortyTwoCachePlugin) =
    new UserSessionExternalIdCache((outerRepo, 24 hours))

  @Singleton
  @Provides
  def externalUserIdCache(outerRepo: FortyTwoCachePlugin) =
    new ExternalUserIdCache((outerRepo, 24 hours))

  @Singleton
  @Provides
  def userExperimentCache(outerRepo: FortyTwoCachePlugin) =
    new UserExperimentCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def sliderHistoryUserIdCache(outerRepo: FortyTwoCachePlugin) =
    new SliderHistoryUserIdCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def bookmarkCountCache(outerRepo: FortyTwoCachePlugin) =
    new BookmarkCountCache((outerRepo, 1 hour))

  @Singleton
  @Provides
  def commentCountUriIdCache(outerRepo: FortyTwoCachePlugin) =
    new CommentCountUriIdCache((outerRepo, 1 hour))

  @Singleton
  @Provides
  def userValueCache(outerRepo: FortyTwoCachePlugin) =
    new UserValueCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def browsingHistoryUserIdCache(outerRepo: FortyTwoCachePlugin) =
    new BrowsingHistoryUserIdCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def clickHistoryUserIdCache(outerRepo: FortyTwoCachePlugin) =
    new ClickHistoryUserIdCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def activeExperimentsCache(outerRepo: FortyTwoCachePlugin) =
    new ActiveExperimentsCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def userConnectionIdCache(outerRepo: FortyTwoCachePlugin) =
    new UserConnectionIdCache((outerRepo, 7 days))
}

@Singleton
class HashMapMemoryCache extends InMemoryCachePlugin {

  val cache = ConcurrentMap[String, Any]()

  def get(key: String): Option[Any] = {
    val value = cache.get(key)
    value
  }

  def remove(key: String) {
    cache.remove(key)
  }

  def set(key: String, value: Any, expiration: Int = 0) {
    cache += key -> value
  }

  override def toString = "HashMapMemoryCache"
}
