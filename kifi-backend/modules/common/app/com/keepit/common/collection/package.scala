package com.keepit.common

package object collection {

  implicit class PimpedMap[A, B](val m: Map[A, B]) {
    /**
     * Adds given (key, value) pairs to the map, where each value is wrapped in an Option.
     * For each value, if the Option is None, the pair is dropped.
     */
    def withOpt(optElems: (A, Option[B])*) = {
      m ++ optElems.collect { case (a, b) if b.nonEmpty => (a, b.get) }.toMap
    }

    def mapKeys[C](f: A => C) = m.map { case (k, v) => (f(k), v) }
  }
}
