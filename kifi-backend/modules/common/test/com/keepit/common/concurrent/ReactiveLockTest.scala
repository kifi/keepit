package com.keepit.common.concurrent

import org.specs2.mutable.Specification

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.{ TimeUnit, CountDownLatch }

import scala.concurrent.{ Future, Await }
import scala.concurrent.duration.Duration

class ReactiveLockTest extends Specification {

  implicit val execCtx = ExecutionContext.fj

  "ReactiveLock" should {

    "obey concurrency limit 1 for synchronous tasks" in {
      val testLock = new ReentrantLock()
      val rLock = new ReactiveLock()

      val taskCompletionPreventionLock = new ReentrantLock()
      taskCompletionPreventionLock.lock()

      var output: Seq[Int] = Seq.empty

      val fut1 = rLock.withLock {
        taskCompletionPreventionLock.tryLock(10, TimeUnit.SECONDS)
        output = output :+ 1
        val held = testLock.tryLock()
        if (!held) throw new IllegalStateException(s"There should be no concurrent access!")
        // println("I'm sync task number one") // can be removed?
        Thread.sleep(20) //making sure this task takes a bit of time to provoke races if there are any
        testLock.unlock()
        output = output :+ 1
      }

      val fut2 = rLock.withLock {
        output = output :+ 2
        val held = testLock.tryLock()
        if (!held) throw new IllegalStateException(s"There should be no concurrent access!")
        // println("I'm sync task number two") // can be removed?
        testLock.unlock()
        output = output :+ 2
      }

      val fut3 = rLock.withLock {
        output = output :+ 3
        val held = testLock.tryLock()
        if (!held) throw new IllegalStateException(s"There should be no concurrent access!")
        // println("I'm sync task number three") // can be removed?
        testLock.unlock()
        output = output :+ 3
      }

      val fut4 = rLock.withLock {
        output = output :+ 4
        val held = testLock.tryLock()
        if (!held) throw new IllegalStateException(s"There should be no concurrent access!")
        // println("I'm sync task number four") // can be removed?
        testLock.unlock()
        output = output :+ 4
      }

      val allFuture = Future.sequence(Seq(fut1, fut2, fut3, fut4))

      rLock.running === 1
      rLock.waiting === 3
      taskCompletionPreventionLock.unlock()

      Await.result(allFuture, Duration(30, "seconds"))
      allFuture.value.get.isSuccess === true
      output === Seq(1, 1, 2, 2, 3, 3, 4, 4)
    }

    "not allow more then max queue size tasks to be in the queue" in {
      val testLock = new ReentrantLock()
      val rLock = new ReactiveLock(1, Some(2))

      var output: Seq[Int] = Seq.empty

      val fut1 = rLock.withLockFuture {
        Future {
          output = output :+ 1
          val held = testLock.tryLock()
          if (!held) throw new IllegalStateException(s"There should be no concurrent access!")
          Thread.sleep(40) //making sure this task takes a bit of time to provoke races if there are any
          // println("I'm async task number one") // can be removed?
          testLock.unlock()
          output = output :+ 1
          true
        }
      }

      val fut2 = rLock.withLockFuture {
        Future {
          output = output :+ 2
          val held = testLock.tryLock()
          Thread.sleep(40) //making sure this task takes a bit of time to provoke races if there are any
          if (!held) throw new IllegalStateException(s"There should be no concurrent access!")
          // println("I'm async task number two") // can be removed?
          testLock.unlock()
          output = output :+ 2
        }
      }

      val fut3 = rLock.withLockFuture {
        Future {
          output = output :+ 3
          val held = testLock.tryLock()
          Thread.sleep(40) //making sure this task takes a bit of time to provoke races if there are any
          if (!held) throw new IllegalStateException(s"There should be no concurrent access!")
          // println("I'm async task number two") // can be removed?
          testLock.unlock()
          output = output :+ 3
        }
      }

      try {
        rLock.withLockFuture {
          Future {
            output = output :+ 4
          }
        }
        failure("task should have not been executed!")
      } catch {
        case e: Exception => //good!
      }

      val allFuture = Future.sequence(Seq(fut1, fut2, fut3))
      Await.result(allFuture, Duration(50, "seconds"))
      allFuture.value.get.isSuccess === true
      output === Seq(1, 1, 2, 2, 3, 3)
    }

    "obey concurrency limit 1 for asynchronous tasks" in {
      val testLock = new ReentrantLock()
      val rLock = new ReactiveLock()

      var output: Seq[Int] = Seq.empty

      val fut1 = rLock.withLockFuture {
        Future {
          output = output :+ 1
          val held = testLock.tryLock()
          if (!held) throw new IllegalStateException(s"There should be no concurrent access!")
          Thread.sleep(20) //making sure this task takes a bit of time to provoke races if there are any
          // println("I'm async task number one") // can be removed?
          testLock.unlock()
          output = output :+ 1
          true
        }
      }

      val fut2 = rLock.withLockFuture {
        Future {
          output = output :+ 2
          val held = testLock.tryLock()
          if (!held) throw new IllegalStateException(s"There should be no concurrent access!")
          // println("I'm async task number two") // can be removed?
          testLock.unlock()
          output = output :+ 2
        }
      }

      val fut3 = rLock.withLockFuture {
        Future {
          output = output :+ 3
          val held = testLock.tryLock()
          if (!held) throw new IllegalStateException(s"There should be no concurrent access!")
          // println("I'm async task number three") // can be removed?
          testLock.unlock()
          output = output :+ 3
        }
      }

      val fut4 = rLock.withLockFuture {
        Future {
          output = output :+ 4
          val held = testLock.tryLock()
          if (!held) throw new IllegalStateException(s"There should be no concurrent access!")
          // println("I'm async task number four") // can be removed?
          testLock.unlock()
          output = output :+ 4
        }
      }

      val allFuture = Future.sequence(Seq(fut1, fut2, fut3, fut4))
      Await.result(allFuture, Duration(30, "seconds"))
      allFuture.value.get.isSuccess === true
      output === Seq(1, 1, 2, 2, 3, 3, 4, 4)
    }

    "obey concurrecy limit 2 exactly (i.e. two tasks running concurrently) for synchronous tasks" in {
      val runningCounter = new AtomicInteger(0)
      val rLock = new ReactiveLock(2)
      val taskCompletionPreventionLock = new CountDownLatch(1)

      val fut1 = rLock.withLock {
        val runningCount = runningCounter.incrementAndGet()
        runningCount must be_<=(2)
        runningCount must be_>=(1)
        rLock.running must be_<=(2)
        // println("I'm concurrent task number one being prevented from completing") // can be removed?
        taskCompletionPreventionLock.await(10, TimeUnit.SECONDS)
        // println("I'm concurrent task number one being done") // can be removed?
        runningCounter.decrementAndGet()
      }

      val fut2 = rLock.withLock {
        val runningCount = runningCounter.incrementAndGet()
        Thread.sleep(20) //making sure this task takes a bit of time to provoke races if there are any
        runningCount === 2
        rLock.running === 2
        rLock.running must be_<=(2)
        // println("I'm concurrent task number two") // can be removed?
        taskCompletionPreventionLock.countDown()
        runningCounter.decrementAndGet()
      }

      val fut3 = rLock.withLock {
        val runningCount = runningCounter.incrementAndGet()
        runningCount must be_<=(2)
        runningCount must be_>=(1)
        rLock.running must be_<=(2)
        // println("I'm concurrent task number three") // can be removed?
        runningCounter.decrementAndGet()
      }

      val fut4 = rLock.withLock {
        val runningCount = runningCounter.incrementAndGet()
        runningCount must be_<=(2)
        runningCount must be_>=(1)
        rLock.running must be_<=(2)
        // println("I'm concurrent task number four") // can be removed?
        runningCounter.decrementAndGet()
      }

      val allFuture = Future.sequence(Seq(fut1, fut2, fut3, fut4))
      Await.result(allFuture, Duration(30, "seconds"))
      allFuture.value.get.isSuccess === true

    }

  }
}
