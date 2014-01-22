package com.keepit.common.cache

object TransactionalCache {
  implicit def getObjectCache[K <: Key[T], T](cache: TransactionalCache[K, T])(implicit txn: TransactionalCaching): ObjectCache[K, T] = cache(txn)
}

abstract class TransactionalCache[K <: Key[T], T](cache: ObjectCache[K, T], name: Option[String] = None) {
  val cacheName = name.getOrElse(this.getClass.getName)

  def apply(txn: TransactionalCaching): ObjectCache[K, T] = {
    if (txn.inCacheTransaction) {
      txn.getOrCreate(cacheName){ new TransactionLocalCache(cache) }
    } else {
      cache
    }
  }

  def direct: ObjectCache[K, T] = cache
}

