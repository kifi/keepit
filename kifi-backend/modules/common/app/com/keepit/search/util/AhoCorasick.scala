package com.keepit.search.util

import scala.collection.immutable.HashMap
import scala.collection.mutable.Queue
import scala.annotation.tailrec
import scala.math._

object AhoCorasick {

  abstract class State[D] {
    def check(position: Int, onMatch: (Int, D) => Unit): Unit
    def matched: Boolean
    def reset(): State[D]
  }

  def apply[I, D](dict: Seq[(Seq[I], D)]) = new AhoCorasick(dict)
}

class AhoCorasick[I, D](dict: Seq[(Seq[I], D)]) {
  import AhoCorasick.State

  private class StateImpl extends State[D] {
    var nextStates: HashMap[I, StateImpl] = HashMap.empty[I, StateImpl]
    var failState: StateImpl = null
    private[this] var hasMatch = false
    private[this] var data: Option[D] = None

    def next(item: I): Option[StateImpl] = nextStates.get(item)

    private[AhoCorasick] def nextOrCreate(item: I): StateImpl = {
      nextStates.get(item) match {
        case Some(nextState) =>
          nextState
        case _ =>
          val nextState = new StateImpl
          nextStates += (item -> nextState)
          nextState
      }
    }

    private[AhoCorasick] def set(d: D) = {
      if (!hasMatch) _size += 1
      hasMatch = true
      data = Some(d)
    }

    private[AhoCorasick] def setFailState(state: StateImpl) = {
      hasMatch |= state.matched
      failState = state
    }

    def check(position: Int, onMatch: (Int, D) => Unit): Unit = {
      if (hasMatch) {
        data.foreach(data => onMatch(position, data))
        if (failState != null) failState.check(position, onMatch)
      }
    }

    def matched: Boolean = hasMatch

    def reset(): State[D] = _root
  }

  private[this] var _size: Int = 0
  def size: Int = _size

  private[this] val _root: StateImpl = new StateImpl
  def initialState: State[D] = _root

  val maxLength: Int = makeTrie(dict)
  makeFailureLinks()

  private def makeTrie(dict: Seq[(Seq[I], D)]): Int = {
    var maxLen: Int = 0
    dict.foreach {
      case (sequence, data) =>
        maxLen = max(maxLen, sequence.size)
        sequence.foldLeft(_root) { (state, item) => state.nextOrCreate(item) }.set(data)
    }
    maxLen
  }

  private def makeFailureLinks() {
    val queue: Queue[StateImpl] = Queue()
    _root.nextStates.foreach { case (_, s) => queue += s }

    while (queue.nonEmpty) {
      val s = queue.dequeue()
      s.nextStates.foreach {
        case (item, next) =>
          queue += next
          val f = if (s.failState != null) s.failState else _root
          f.next(item).foreach(next.setFailState(_))
      }
    }
  }

  def scan(iter: Iterator[I])(onMatch: (Int, D) => Unit): Unit = {
    var position = -1
    iter.foldLeft(_root) { (s, item) =>
      position += 1
      val next = _next(item, s)
      next.check(position, onMatch)
      next
    }
  }

  def next(item: I, s: State[D]): State[D] = _next(item, s.asInstanceOf[StateImpl])

  @tailrec private def _next(item: I, s: StateImpl): StateImpl = {
    s.next(item) match {
      case Some(next) => next
      case _ =>
        if (s eq _root) _root else {
          _next(item, if (s.failState != null) s.failState else _root)
        }
    }
  }
}

