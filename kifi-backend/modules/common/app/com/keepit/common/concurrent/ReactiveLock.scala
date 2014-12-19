package com.keepit.common.concurrent

import scala.concurrent.{ Future, Promise, ExecutionContext => EC, Lock }

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class ReactiveLock(numConcurrent: Int = 1) {
  require(numConcurrent > 0, "Concurrency degree must be strictly positive!")

  private case class QueuedItem[T](runner: () => Future[T], promise: Promise[T], ec: EC)

  private val taskQueue = new ConcurrentLinkedQueue[QueuedItem[_]]()

  private val waitingCount = new AtomicInteger(0)

  private var runningCount: Int = 0

  private val lock = new Lock()

  //note that all this does inside of the synchronized block is start a future (in the worst case),
  //so actual synchonization is only held for a *very* short amount of time for each task
  private def dispatch(): Unit = lock.synchronized {
    if (runningCount < numConcurrent) {
      val candidate: Option[QueuedItem[_]] = Option(taskQueue.poll())
      candidate.foreach {
        case QueuedItem(runner, promise, ec) => {
          runningCount += 1
          waitingCount.decrementAndGet()
          val fut = runner()
          fut.onComplete { _ =>
            lock.synchronized {
              runningCount -= 1
              dispatch()
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
    dispatch()
    p.future
  }

  def withLockFuture[T](f: => Future[T])(implicit ec: EC): Future[T] = {
    val p = Promise[T]
    //making sure the runner just starts a future and does nothing else (in case f does some other work, keeping dispatch very light)
    val wrapper = () => {
      val innerPromise = Promise[T]
      innerPromise.completeWith(Future(f).flatMap(future => future))
      innerPromise.future
    }
    taskQueue.add(QueuedItem[T](wrapper, p, ec))
    waitingCount.incrementAndGet()
    dispatch()
    p.future
  }

  def waiting: Int = waitingCount.get()

  def running: Int = runningCount

  def clear(): Unit = lock.synchronized {
    taskQueue.clear()
  }

}

