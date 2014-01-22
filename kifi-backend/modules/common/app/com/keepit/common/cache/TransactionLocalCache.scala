package com.keepit.common.cache

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration
import scala.concurrent.Future


class TransactionLocalCache[K <: Key[T], T] private(val ttl: Duration, override val outerCache: Option[ObjectCache[K, T]], underlying: ObjectCache[K, T]) extends ObjectCache[K, T] {
  // outerCache is wrapped in ReadOnlyCacheWrapper to block updates silently
  def this(underlying: ObjectCache[K, T]) = this(Duration.Inf, Some(new ReadOnlyCacheWrapper(underlying)), underlying)

  private[this] val txnLocalStore = TrieMap.empty[K, ObjectState[T]]

  protected[cache] def getFromInnerCache(key: K): ObjectState[T] = {
    txnLocalStore.get(key) match {
      case Some(state) => state
      case None => NotFound() // failed to find the key, go to outerCache
    }
  }

  protected[cache] def setInnerCache(key: K, valueOpt: Option[T]) = {
    txnLocalStore += (key -> Found(valueOpt))
  }

  protected[cache] def bulkGetFromInnerCache(keys: Set[K]): Map[K, Option[T]] = {
    keys.foldLeft(Map.empty[K, Option[T]]){ (result, key) =>
      txnLocalStore.get(key) match {
        case Some(state) =>
          state match {
            case Found(valueOpt) => result + (key -> valueOpt)
            case Removed() => result + (key -> None)
            case state => throw new Exception(s"this state ($state) shouldn't be in the cache")
          }
        case None => result
      }
    }
  }

  def remove(key: K): Unit = {
    txnLocalStore += (key -> Removed())
  }

  def flush(): Unit = {
    // all changes made in the transaction are going to be flushed directly to the underlying cache
    txnLocalStore.foreach{ case (key, valueOptOpt) =>
      valueOptOpt match {
        case Found(valueOpt) => underlying.set(key, valueOpt)
        case Removed() => underlying.remove(key)
        case state => throw new Exception(s"this state ($state) shouldn't be in the cache")
      }
    }
  }
}

class ReadOnlyCacheWrapper[K <: Key[T], T](underlying: ObjectCache[K, T]) extends ObjectCache[K, T] {
  val ttl: Duration = Duration.Inf

  protected[cache] def getFromInnerCache(key: K): ObjectState[T] = underlying.getFromInnerCache(key)

  protected[cache] def setInnerCache(key: K, valueOpt: Option[T]) = { } // ignore silently

  protected[cache] def bulkGetFromInnerCache(keys: Set[K]): Map[K, Option[T]] = underlying.bulkGetFromInnerCache(keys)

  def remove(key: K): Unit = { } // ignore silently
}
