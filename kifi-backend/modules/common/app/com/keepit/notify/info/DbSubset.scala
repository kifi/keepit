package com.keepit.notify.info

import com.keepit.common.db.Id
import com.keepit.common.store.ImagePath
import com.keepit.model._

trait DbSubset {

  def lookup[M](kind: String, id: Id[M]): Any

}

class MapDbSubset(
    val objMap: Map[String, Map[Id[_], Any]]) extends DbSubset {

  def lookup[M](kind: String, id: Id[M]): Any = objMap(kind)(id)

}

trait DbSubsetProvider[F[_]] {
  def submit[A](using: UsingDbSubset[A]): F[A]
}

case class UsingDbSubset[A](needs: Seq[NeedInfo[_, _]])(fn: DbSubset => A)
