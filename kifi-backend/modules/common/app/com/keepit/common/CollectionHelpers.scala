package com.keepit.common

import com.keepit.common.core._

object CollectionHelpers {
  def interleaveBy[R, T](priorityStreams: Stream[R]*)(f: R => T)(implicit ord: Ordering[T]): Stream[R] = {
    priorityStreams.foldLeft(Stream.empty[R]) { case (aggregatedStream, nextStream) => interleaveBy(aggregatedStream, nextStream)(f) }
  }

  private def interleaveBy[R, T](firstPriorityStream: Stream[R], secondPriorityStream: Stream[R])(f: R => T)(implicit ord: Ordering[T]): Stream[R] = {
    (firstPriorityStream.headOption, secondPriorityStream.headOption) match {
      case (_, None) => firstPriorityStream
      case (None, _) => secondPriorityStream
      case (Some(firstHead), Some(secondHead)) => {
        if (ord.lt(f(firstHead), f(secondHead))) secondHead #:: interleaveBy(firstPriorityStream, secondPriorityStream.tail)(f)
        else firstHead #:: interleaveBy(firstPriorityStream.tail, secondPriorityStream)(f) // stable
      }
    }
  }

  def dedupBy[A, B](items: Seq[A])(toKey: A => B): Seq[A] = { // stable
    val alreadySeen = new scala.collection.mutable.HashSet[B]
    items.filter { item =>
      val key = toKey(item)
      !alreadySeen(key) tap { notSeen => if (notSeen) alreadySeen += key }
    }
  }

  implicit class Cycle[A](seq: Iterable[A]) {
    def cycle(offset: Int): Iterable[A] = {
      val firstChunk = seq.take(offset)
      val secondChunk = seq.drop(offset)
      secondChunk ++ firstChunk
    }
  }
}
