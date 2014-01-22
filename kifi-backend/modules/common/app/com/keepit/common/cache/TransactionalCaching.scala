package com.keepit.common.cache

import scala.collection.concurrent.TrieMap
import com.keepit.common.logging.Logging

object TransactionalCaching {
  object Implicits {
    implicit val directCacheAccess = new TransactionalCaching with Logging {
      override def inCacheTransaction: Boolean = false
    }
  }
}

trait TransactionalCaching { self: Logging =>
  private[this] val caches = TrieMap.empty[String, TransactionLocalCache[_, _]]

  private[this] var inTxn: Boolean = false

  def inCacheTransaction: Boolean = inTxn

  def getOrCreate[K <: Key[T], T](cacheName: String)(createNewCache: => TransactionLocalCache[K, T]) : ObjectCache[K, T] = {
    caches.get(cacheName) match {
      case Some(cache) => cache.asInstanceOf[ObjectCache[K, T]]
      case _ =>
        val newCache = createNewCache
        caches.putIfAbsent(cacheName, newCache) match {
          case Some(existing) => existing.asInstanceOf[ObjectCache[K, T]]
          case _ => newCache
        }
    }
  }

  def beginCacheTransaction() = {
    inTxn = true
  }

  def commitCacheTransaction(): Unit = {
    try {
      caches.foreach{ case (name, cache) =>
        try {
          cache.flush()
        } catch {
          case ex: Throwable => log.error("error during flush", ex)
        }
      }
    } finally {
      caches.clear()
      inTxn = false
    }
  }

  def rollbackCacheTransaction() = {
    caches.clear
    inTxn = false
  }
}