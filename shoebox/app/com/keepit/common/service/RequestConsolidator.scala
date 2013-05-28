package com.keepit.common.service

import com.keepit.common.logging.Logging
import scala.collection.concurrent.{TrieMap=>ConcurrentMap}
import scala.concurrent.duration._
import scala.concurrent.Future
import java.lang.ref.WeakReference
import java.lang.ref.ReferenceQueue

class RequestConsolidator[K, T](ttl: Duration) extends Logging {

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
        if (existingFuture != null && now < ref.expireBy) {
          log.info("request found. sharing future.")
          existingFuture
        } else {
          log.info("request expired. creating future.")
          val future = newFuture(key)
          futureRefMap.put(key, new FutureRef(key, future, now + ttlMillis))
          future
        }
      case _ =>
        log.info("request not found. creating future.")
        val future = newFuture(key)
        futureRefMap.put(key, new FutureRef(key, future, now + ttlMillis))
        future
    }
  }
}
