package com.keepit.common.service

import com.keepit.common.logging.Logging
import scala.collection.concurrent.{ TrieMap => ConcurrentMap }
import scala.concurrent._
import scala.concurrent.duration._
import java.lang.ref.WeakReference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.atomic.AtomicReference
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.akka.SafeFuture

class RequestConsolidator[K, T](ttl: Duration) extends Logging {

  private[this] val ttlMillis = ttl.toMillis

  private class FutureRef[T](val key: K, future: Future[T], val expireBy: Long, refQueue: ReferenceQueue[Future[T]]) extends WeakReference[Future[T]](future, refQueue)

  private[this] val referenceQueue = new ReferenceQueue[Future[T]]
  private[this] val futureRefMap = new ConcurrentMap[K, FutureRef[T]]

  private[this] val cleanerRef = new AtomicReference[Future[Unit]](null)

  private def clean() {
    var ref = referenceQueue.poll().asInstanceOf[FutureRef[T]]
    if (ref != null) {
      val cleaner = cleanerRef.get
      if (cleaner == null || cleaner.isCompleted) {
        val promise = Promise[Unit]
        if (cleanerRef.compareAndSet(cleaner, promise.future)) {
          promise completeWith SafeFuture {
            try {
              val now = System.currentTimeMillis
              var cnt = 0
              while (ref != null) {
                if (futureRefMap.remove(ref.key, ref)) cnt += 1
                ref = referenceQueue.poll().asInstanceOf[FutureRef[T]]
              }
              log.info(s"$cnt entries cleaned in ${System.currentTimeMillis - now}ms")
            } finally {
              cleanerRef.compareAndSet(promise.future, null)
            }
          }
        }
      }
    }
  }

  def apply(key: K)(newFuture: K => Future[T]): Future[T] = {
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

  def set(key: K, future: Future[T]): Unit = {
    val now = System.currentTimeMillis
    futureRefMap.put(key, new FutureRef(key, future, now + ttlMillis, referenceQueue))
  }

  def remove(key: K): Unit = {
    futureRefMap.remove(key)
  }

  def clear() { futureRefMap.clear() }
}
