package com.keepit.common.service

import scala.collection.concurrent.{TrieMap=>ConcurrentMap}
import scala.concurrent.duration._
import scala.concurrent.Future
import java.lang.ref.WeakReference
import java.lang.ref.ReferenceQueue

class RequestConsolidator[K, T](ttl: Duration) {

  private[this] val ttlMillis = ttl.toMillis

  private class FutureRef[T](val key: K, future: Future[T], val expireBy: Long) extends WeakReference[Future[T]](future)

  private[this] val referenceQueue = new ReferenceQueue[FutureRef[T]]
  private[this] val futureRefMap = new ConcurrentMap[K, FutureRef[T]]

  private def clean() {
    var ref = referenceQueue.poll()
    while (ref != null) {
      futureRefMap.remove(ref.asInstanceOf[FutureRef[T]].key)
      ref = referenceQueue.poll()
    }
  }

  def apply(key: K)(newFuture: K=>Future[T]): Future[T] = {
    clean()

    val now = System.currentTimeMillis

    futureRefMap.get(key) match {
      case Some(ref) =>
        val existingFuture = ref.get
        if (existingFuture != null && ref.expireBy < now) existingFuture
        else {
          val future = newFuture(key)
          futureRefMap.put(key, new FutureRef(key, future, now + ttlMillis))
          future
        }
      case _ =>
        val future = newFuture(key)
        futureRefMap.put(key, new FutureRef(key, future, now + ttlMillis))
        future
    }
  }
}
