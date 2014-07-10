package com.keepit.common.cache

import com.keepit.serializer.{SafeLocalSerializer, LocalSerializer, Serializer}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration
import com.keepit.common.performance._
import com.keepit.common.logging.Logging

class TransactionLocalCache[K <: Key[T], T] private(
  override val minTTL: Duration,
  override val maxTTL: Duration,
  override val outerCache: Option[ObjectCache[K, T]],
  underlying: ObjectCache[K, T],
  serializer: Serializer[T],
  localSerializerOpt: Option[LocalSerializer[T]]
) extends ObjectCache[K, T] with Logging {

  // outerCache is wrapped in ReadOnlyCacheWrapper to block updates silently
  def this(underlying: ObjectCache[K, T], serializer: Serializer[T], localSerializerOpt:Option[LocalSerializer[T]]) =
    this(Duration.Inf, Duration.Inf, Some(new ReadOnlyCacheWrapper(underlying)), underlying, serializer, localSerializerOpt)

  val localSerializer:LocalSerializer[T] = localSerializerOpt.getOrElse(SafeLocalSerializer(serializer))

  sealed trait LocalObjectState
  case class LFound(value: Any) extends LocalObjectState
  case class LNotFound() extends LocalObjectState
  case class LRemoved() extends LocalObjectState

  private[this] val txnLocalStore = TrieMap.empty[K, LocalObjectState]

  protected[cache] def getFromInnerCache(key: K): ObjectState[T] = {
    txnLocalStore.get(key) match {
      case Some(state) =>
        state match {
          case LFound(serialized) => Found(localSerializer.localReads(serialized))
          case LNotFound() => NotFound()
          case LRemoved() => Removed()
        }
      case None => NotFound() // failed to find the key, go to outerCache
    }
  }

  protected[cache] def setInnerCache(key: K, valueOpt: Option[T]) = {
    txnLocalStore += (key -> LFound(localSerializer.localWrites(valueOpt)))
  }

  protected[cache] def bulkGetFromInnerCache(keys: Set[K]): Map[K, ObjectState[T]] = {
    keys.foldLeft(Map.empty[K, ObjectState[T]]){ (result, key) =>
      txnLocalStore.get(key) match {
        case Some(state) =>
          state match {
            case LFound(serialized) => result + (key -> Found(localSerializer.localReads(serialized)))
            case LRemoved() => result + (key -> Removed())
            case state => throw new Exception(s"this state ($state) should not be in the cache")
          }
        case None => result + (key -> NotFound())
      }
    }
  }

  def remove(key: K): Unit = {
    txnLocalStore += (key -> LRemoved())
  }

  def flush(): Unit = {
    // all changes made in the transaction are going to be flushed directly to the underlying cache
    txnLocalStore.foreach{ case (key, state) =>
      state match {
        case LFound(serialized) => {
          val v = timing(s"TLCache-reads($key,${state.toString.take(500)})", 50) {
            localSerializer.localReads(serialized)
          }
          timing(s"TLCache-writes($key,${state.toString.take(500)},$v)", 50) {
            underlying.set(key, v)
          }
        }
        case LRemoved() => underlying.remove(key)
        case state => throw new Exception(s"this state ($state) should not be in the cache")
      }
    }
  }
}

class ReadOnlyCacheWrapper[K <: Key[T], T](underlying: ObjectCache[K, T], logging: Boolean = false) extends ObjectCache[K, T] {
  import CacheStatistics.cacheLog
  val minTTL: Duration = Duration.Inf
  val maxTTL: Duration = Duration.Inf

  protected[cache] def getFromInnerCache(key: K): ObjectState[T] = underlying.getFromInnerCache(key)

  protected[cache] def setInnerCache(key: K, valueOpt: Option[T]) = {
    if (logging) cacheLog.warn(s"cache ignoring set key $key")
  } // ignore silently

  protected[cache] def bulkGetFromInnerCache(keys: Set[K]): Map[K, ObjectState[T]] = underlying.bulkGetFromInnerCache(keys)

  def remove(key: K): Unit = {
    if (logging) cacheLog.warn(s"cache ignoring remove key $key")
  } // ignore silently
}
