package com.keepit.common.concurrent

import com.keepit.common.logging.Logging
import org.specs2.mutable.Specification

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }
import scala.util.Random

class SyncListTest extends Specification with Logging {

  implicit val execCtx = ExecutionContext.fj

  "SyncListTest" should {
    def await[T](t: Task[T]): T = Await.result(t.run, Duration.Inf)
    "work at all" in {
      val input = Seq.range(1, 5)
      val sl = SyncList.independent(input)(x => Future.successful(x))
      await(sl.seq) === input
      await(sl.take(2).seq) === input.take(2)
      await(sl.take(0).seq) === input.take(0)
      await(sl.drop(3).seq) === input.drop(3)
      await(sl.head) === input.head
      await(sl.drop(input.length).head) must throwA[NoSuchElementException]
      await(sl.dropWhile(_ < 3).seq) === input.dropWhile(_ < 3)
      await(sl.takeWhile(_ < 3).seq) === input.takeWhile(_ < 3)
      await(sl.find(_ == 3)) === input.find(_ == 3)
      await(sl.find(_ == 42)) === input.find(_ == 42)

      await(sl.exists(_ == 3)) === input.exists(_ == 3)
      await(sl.exists(_ == 42)) === input.exists(_ == 42)
    }
    "do cool iterate things" in {
      def fast(n: Int) = if (n % 2 == 0) n / 2 else 3 * n + 1
      def slow(n: Int) = Future.successful(fast(n))
      val sl = SyncList.iterate(3)(slow)
      val ref = Stream.iterate(3)(fast)
      await(sl.take(20).seq) === ref.take(20).toSeq
    }
    "use fold to accumulate elements of a map" in {
      val fakeDb = Map(1 -> "one", 3 -> "three", 4 -> "four", 5 -> "five")
      val input = Set(1, 2, 3)
      def fast(id: Int): Option[String] = fakeDb.get(id)
      def slow(id: Int): Future[Option[String]] = Future.successful(fast(id))

      val sl = SyncList.independent(Set(1, 2, 3))(id => slow(id).map(id -> _))
      val ref = input.map(id => id -> fast(id)).toMap
      await(sl.foldLeft[Map[Int, Option[String]]](Map.empty)(_ + _)) === ref
    }
    "use fold to page through a list" in {
      val fakeDb = Random.shuffle(Seq.range(1, 1000)).take(100).sorted
      def fast(fromId: Option[Int], pageSize: Int): Seq[Int] = fromId.fold(fakeDb)(id => fakeDb.dropWhile(_ <= id)).take(pageSize)
      def slow(fromId: Option[Int], pageSize: Int): Future[Seq[Int]] = Future.successful(fast(fromId, pageSize))

      val ref = fakeDb.grouped(10).toSeq
      val sl = SyncList.accumulate(Option.empty[Int]) { lastId =>
        slow(lastId, 10).map { page => (page, page.lastOption orElse lastId) }
      }.take(10)

      await(sl.seq) === ref

      await(sl.foldLeft(0)(_ + _.sum)) === ref.foldLeft(0)(_ + _.sum)
      await(sl.foldLeft(0)(_ + _.sum)) === fakeDb.sum
    }
    "do cool memoization things" in {
      val fakeDb = Random.shuffle(Seq.range(1, 1000)).take(100).sorted
      def fast(fromId: Option[Int], pageSize: Int): Seq[Int] = fromId.fold(fakeDb)(id => fakeDb.dropWhile(_ <= id)).take(pageSize)

      var serverPings = 0
      def slow(fromId: Option[Int], pageSize: Int): Future[Seq[Int]] = {
        serverPings += 1
        Future.successful(fast(fromId, pageSize))
      }

      val ref = fakeDb.grouped(10).toSeq
      val sl = SyncList.accumulate(Option.empty[Int]) { lastId =>
        slow(lastId, 10).map { page => (page, page.lastOption orElse lastId) }
      }.take(10)

      serverPings === 0
      val fullPages = sl.seq
      val sum = sl.foldLeft(0)(_ + _.sum)
      serverPings === 0

      await(fullPages) === ref
      serverPings === 10
      await(sum) === ref.foldLeft(0)(_ + _.sum)
      serverPings === 20 // TODO(ryan): I feel like it should be possible to get this to 10, but being smart is hard
      await(sum) === fakeDb.sum
      serverPings === 20
    }
  }
}
