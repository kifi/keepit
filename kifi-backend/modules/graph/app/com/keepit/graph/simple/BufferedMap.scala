package com.keepit.graph.simple

import scala.collection.mutable.{ Map => MutableMap, Set => MutableSet }

class BufferedMap[A, B](current: MutableMap[A, B], updated: MutableMap[A, B] = MutableMap[A, B](), removed: MutableSet[A] = MutableSet[A]()) extends MutableMap[A, B] {
  def get(key: A) = if (removed.contains(key)) None else updated.get(key) orElse current.get(key)
  def iterator = updated.iterator ++ current.iterator.filterNot { case (key, _) => removed.contains(key) || updated.contains(key) }
  def +=(kv: (A, B)) = {
    removed -= kv._1
    updated += kv
    this
  }
  def -=(key: A) = {
    removed += key
    updated -= key
    this
  }
  def flush(): Unit = {
    current --= removed
    current ++= updated
  }
  def containsUpdate(key: A): Boolean = updated.contains(key)
}
