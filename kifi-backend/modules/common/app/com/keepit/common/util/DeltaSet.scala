package com.keepit.common.util

import play.api.libs.json._
import play.api.libs.functional.syntax._

case class DeltaSet[T](djs: DisjointSets[T, DeltaSet.AddOrRemove]) {
  def addAll(items: Set[T]) = this.copy(djs = djs.add(items, DeltaSet.Add))
  def removeAll(items: Set[T]) = this.copy(djs = djs.add(items, DeltaSet.Remove))

  def add(items: T*) = addAll(items.toSet)
  def remove(items: T*) = removeAll(items.toSet)

  def added: Set[T] = djs.getSet(DeltaSet.Add)
  def removed: Set[T] = djs.getSet(DeltaSet.Remove)
  def all: Set[T] = djs.elems.keySet

  def map[S](f: T => S): DeltaSet[S] = this.copy(djs = djs.map(f))
}

object DeltaSet {
  sealed trait AddOrRemove
  case object Add extends AddOrRemove
  case object Remove extends AddOrRemove

  def empty[T]: DeltaSet[T] = DeltaSet(DisjointSets.empty[T, AddOrRemove])

  private def fromSets[T](added: Set[T], removed: Set[T]): DeltaSet[T] = DeltaSet.empty.addAll(added).removeAll(removed)
  implicit def reads[T](implicit tReads: Reads[T]): Reads[DeltaSet[T]] = (
    (__ \ 'add).readNullable[Set[T]].map(_ getOrElse Set.empty) and
    (__ \ 'remove).readNullable[Set[T]].map(_ getOrElse Set.empty)
  )(fromSets[T] _)
}

