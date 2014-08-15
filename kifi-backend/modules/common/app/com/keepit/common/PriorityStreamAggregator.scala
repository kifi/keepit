package com.keepit.common

object PriorityStreamAggregator {
  def aggregateBy[R, T](priorityStreams: Stream[R]*)(f: R => T)(implicit ord: Ordering[T]): Stream[R] = {
    priorityStreams.reduceLeft((aggregatedStream, nextStream) => aggregateBy(aggregatedStream, nextStream)(f))
  }

  private def aggregateBy[R, T](firstPriorityStream: Stream[R], secondPriorityStream: Stream[R])(f: R => T)(implicit ord: Ordering[T]): Stream[R] = {
    (firstPriorityStream.headOption, secondPriorityStream.headOption) match {
      case (_, None) => firstPriorityStream
      case (None, _) => secondPriorityStream
      case (Some(firstHead), Some(secondHead)) => {
        if (ord.lt(f(firstHead), f(secondHead))) secondHead #:: aggregateBy(firstPriorityStream, secondPriorityStream.tail)(f)
        else firstHead #:: aggregateBy(firstPriorityStream.tail, secondPriorityStream)(f) // stable
      }
    }
  }
}
