package com.keepit.common.util

case class DeltaSet[T](djs: DisjointSets[T, DeltaSet.AddOrRemove]) {
  def addAll(items: Set[T]) = this.copy(djs = djs.add(items, DeltaSet.Add))
  def removeAll(items: Set[T]) = this.copy(djs = djs.add(items, DeltaSet.Remove))

  def add(items: T*) = addAll(items.toSet)
  def remove(items: T*) = removeAll(items.toSet)

  def added: Set[T] = djs.getSet(DeltaSet.Add)
  def removed: Set[T] = djs.getSet(DeltaSet.Remove)
}

object DeltaSet {
  sealed trait AddOrRemove
  case object Add extends AddOrRemove
  case object Remove extends AddOrRemove

  def empty[T]: DeltaSet[T] = DeltaSet(DisjointSets.empty[T, AddOrRemove])
}

