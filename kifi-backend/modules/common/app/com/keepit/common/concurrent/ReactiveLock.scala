package com.keepit.common.concurrent

import scala.concurrent.{ Future, Promise, ExecutionContext => EC }

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class ReactiveLock() {

  private case class QueuedItem[T](runner: () => Future[T], promise: Promise[T], ec: EC)

  private val taskQueue = new ConcurrentLinkedQueue[QueuedItem[_]]()

  private val waitingCount = new AtomicInteger(0)

  private var running: Boolean = false

  //note that all this does inside of the synchronized block is start a future (in the worst case),
  //so actual synchonization is only held for a *very* short amount of time for each task
  private def dispatchOne(): Unit = synchronized {
    if (!running) {
      val candidate: Option[QueuedItem[_]] = Option(taskQueue.poll())
      candidate.map {
        case QueuedItem(runner, promise, ec) => {
          running = true
          waitingCount.decrementAndGet()
          val fut = runner()
          fut.onComplete { _ =>
            synchronized {
              running = false
              dispatchOne()
            }
          }(ec)
          promise.completeWith(fut)
        }
      }
    }
  }

  def withLock[T](f: => T)(implicit ec: EC): Future[T] = {
    val p = Promise[T]
    val wrapper = () => Future { f }
    taskQueue.add(QueuedItem[T](wrapper, p, ec))
    waitingCount.incrementAndGet()
    dispatchOne()
    p.future
  }

  def withLockFuture[T](f: => Future[T])(implicit ec: EC): Future[T] = {
    val p = Promise[T]
    //making sure the runner just starts a future and does nothing else (in case f does some other work, keeping dispatch very light)
    val wrapper = () => {
      val innerPromise = Promise[T]
      Future { innerPromise.completeWith(f) }
      innerPromise.future
    }
    taskQueue.add(QueuedItem[T](wrapper, p, ec))
    waitingCount.incrementAndGet()
    dispatchOne()
    p.future
  }

  def waiting: Int = waitingCount.get()

}

