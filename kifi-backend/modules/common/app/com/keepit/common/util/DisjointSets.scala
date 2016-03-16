package com.keepit.common.util

case class DisjointSets[T, SI](elems: Map[T, SI]) {
  def add(items: Set[T], si: SI) = this.copy(elems = elems ++ items.map(_ -> si).toMap)
  def getSet(si: SI): Set[T] = elems.filter { case (_, setId) => setId == si }.keySet

  def map[S](f: T => S): DisjointSets[S, SI] = this.copy(elems = elems.map { case (k, v) => f(k) -> v })
}

object DisjointSets {
  def empty[T, SI] = DisjointSets(Map.empty[T, SI])
}
