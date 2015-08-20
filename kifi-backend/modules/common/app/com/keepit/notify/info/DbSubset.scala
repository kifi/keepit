package com.keepit.notify.info

import com.keepit.common.db.Id
import com.keepit.common.store.ImagePath
import com.keepit.model._

trait DbSubset {

  // todo might want to rethink this a bit...
  def user(id: Id[User]): User
  def library(id: Id[Library]): Library
  def userImageUrl(id: Id[User]): String
  def keep(id: Id[Keep]): Keep
  def libraryUrl(id: Id[Library]): String
  def libraryInfo(id: Id[Library]): LibraryNotificationInfo
  def libraryOwner(id: Id[Library]): User
  def organization(id: Id[Organization]): Organization
  def organizationInfo(id: Id[Organization]): OrganizationNotificationInfo

}

class MapDbSubset(
    val userMap: Map[Id[User], User] = Map(),
    val libraryMap: Map[Id[Library], Library] = Map(),
    val userImageUrlMap: Map[Id[User], String] = Map(),
    val keepMap: Map[Id[Keep], Keep] = Map(),
    val libraryUrlMap: Map[Id[Library], String] = Map(),
    val libraryInfoMap: Map[Id[Library], LibraryNotificationInfo] = Map(),
    val libraryOwnerMap: Map[Id[Library], User] = Map(),
    val organizationMap: Map[Id[Organization], Organization] = Map(),
    val organizationInfoMap: Map[Id[Organization], OrganizationNotificationInfo] = Map()) extends DbSubset {

  def user(id: Id[User]): User = userMap(id)
  def library(id: Id[Library]): Library = libraryMap(id)
  def userImageUrl(id: Id[User]): String = userImageUrlMap(id)
  def keep(id: Id[Keep]): Keep = keepMap(id)
  def libraryUrl(id: Id[Library]): String = libraryUrlMap(id)
  def libraryInfo(id: Id[Library]): LibraryNotificationInfo = libraryInfoMap(id)
  def libraryOwner(id: Id[Library]): User = libraryOwnerMap(id)
  def organization(id: Id[Organization]): Organization = organizationMap(id)
  def organizationInfo(id: Id[Organization]): OrganizationNotificationInfo = organizationInfoMap(id)

}

trait DbSubsetProvider[F[_]] {
  def submit[A](using: UsingDbSubset[A]): F[A]
}

case class UsingDbSubset[A](needs: Seq[NeedInfo[_]])(fn: DbSubset => A)
