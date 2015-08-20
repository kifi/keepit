package com.keepit.notify.info

import com.keepit.common.db.Id
import com.keepit.model.{Keep, Library, User}

trait DbSubset {

  def user(id: Id[User]): User
  def library(id: Id[Library]): Library
  def userImageUrl(id: Id[User]): String
  def keep(id: Id[Keep]): Keep
  def libraryUrl(id: Id[Library]): String

}

class MapDbSubset(
  val userMap: Map[Id[User], User],
  val libraryMap: Map[Id[Library], Library],
  val userImageUrlMap: Map[Id[User], String],
  val keepMap: Map[Id[Keep], Keep],
  val libraryUrlMap: Map[Id[Library], String]
) extends DbSubset {

  def user(id: Id[User]): User = userMap(id)
  def library(id: Id[Library]): Library = libraryMap(id)
  def userImageUrl(id: Id[User]): String = userImageUrlMap(id)
  def keep(id: Id[Keep]): Keep = keepMap(id)
  def libraryUrl(id: Id[Library]): String = libraryUrlMap(id)

}


trait DbSubsetProvider[F[_]] {
  def submit[A](using: UsingDbSubset[A]): F[A]
}

case class UsingDbSubset[A](needs: Seq[NeedInfo[_]])(fn: DbSubset => A)

object UsingDbSubset {
  def apply[A](needs: NeedInfo[_]*)(fn: DbSubset => A): UsingDbSubset[A] = UsingDbSubset(needs)(fn)
}
