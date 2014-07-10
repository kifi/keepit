package com.keepit.common.cache

import com.google.inject.Singleton
import scala.collection.concurrent.{ TrieMap => ConcurrentMap }

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

  def removeAll(prefix: Option[String]): Unit = {
    if (prefix.isDefined)
      cache --= cache.keysIterator.filter(_.startsWith(prefix.get))
    else
      cache.clear()
  }
}

