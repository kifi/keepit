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

  def addOnly[T](items: Set[T]): DeltaSet[T] = DeltaSet.empty.addAll(items)
  def removeOnly[T](items: Set[T]): DeltaSet[T] = DeltaSet.empty.removeAll(items)

  private def fromSets[T](added: Option[Set[T]], removed: Option[Set[T]]): DeltaSet[T] = DeltaSet.addOnly(added.getOrElse(Set.empty)).removeAll(removed.getOrElse(Set.empty))
  private def toSets[T](delta: DeltaSet[T]): (Option[Set[T]], Option[Set[T]]) = (Some(delta.added).filter(_.isEmpty), Some(delta.removed).filter(_.isEmpty))
  implicit def format[T](implicit tReads: Reads[T], tWrites: Writes[T]): Format[DeltaSet[T]] = (
    (__ \ 'add).formatNullable[Set[T]] and
    (__ \ 'remove).formatNullable[Set[T]]
  )(fromSets, toSets)
}

