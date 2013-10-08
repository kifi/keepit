package com.keepit.common.service

import com.keepit.common.logging.Logging
import scala.collection.concurrent.{TrieMap=>ConcurrentMap}
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.Promise
import java.lang.ref.WeakReference
import java.lang.ref.ReferenceQueue

class RequestConsolidator[K, T](ttl: Duration) extends Logging {

  private[this] val ttlMillis = ttl.toMillis

  private class FutureRef[T](val key: K, future: Future[T], val expireBy: Long, refQueue: ReferenceQueue[Future[T]]) extends WeakReference[Future[T]](future, refQueue)

  private[this] val referenceQueue = new ReferenceQueue[Future[T]]
  private[this] val futureRefMap = new ConcurrentMap[K, FutureRef[T]]

  private def clean() {
    var ref = referenceQueue.poll().asInstanceOf[FutureRef[T]]
    while (ref != null) {
      futureRefMap.remove(ref.key, ref)
      ref = referenceQueue.poll().asInstanceOf[FutureRef[T]]
    }
  }

  def apply(key: K)(newFuture: K=>Future[T]): Future[T] = {
    clean()

    val now = System.currentTimeMillis

    def getFuture(ref: FutureRef[T]): Future[T] = {
      val existingFuture = ref.get
      if (existingFuture != null && now < ref.expireBy) {
        log.debug("request found. sharing future.")
        existingFuture
      } else {
        log.debug("request expired")
        futureRefMap.remove(key, ref)
        null
      }
    }

    var f: Future[T] = futureRefMap.get(key) match {
      case Some(ref) => getFuture(ref)
      case _ => null
    }

    if (f != null) return f

    // there was no future, create one
    log.debug("request not found. creating future.")
    val promise = Promise[T]
    val future = promise.future
    val futureRef = new FutureRef(key, future, now + ttlMillis, referenceQueue)
    while (f == null) {
      f = futureRefMap.putIfAbsent(key, futureRef) match {
        case Some(ref) => getFuture(ref)
        case _ =>
          try {
            promise.completeWith(newFuture(key))
          } catch {
            case t: Throwable => promise.failure(t) // failed to create a new future
          }
          future
      }
    }
    f
  }

  def clear() { futureRefMap.clear() }
}
