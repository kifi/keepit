package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.model.User

abstract class SearchFilter(val idFilter: Set[Long]) {
  val includeMine: Boolean
  val includeFriends: Boolean
  val includeOthers: Boolean
  def filterFriends(f: Set[Id[User]]) = f
}

object SearchFilter {
  def default(idFilter: Set[Long] = Set()) = new SearchFilter(idFilter) {
    val includeMine    = true
    val includeFriends = true
    val includeOthers  = true
  }
  def mine(idFilter: Set[Long] = Set()) = new SearchFilter(idFilter) {
    val includeMine    = true
    val includeFriends = false
    val includeOthers  = false
  }
  def friends(idFilter: Set[Long] = Set()) = new SearchFilter(idFilter) {
    val includeMine    = true
    val includeFriends = true
    val includeOthers  = false
  }
  def custom(idFilter: Set[Long] = Set(), users: Set[Id[User]]) = new SearchFilter(idFilter) {
    val includeMine    = false
    val includeFriends = true
    val includeOthers  = false
    override def filterFriends(f: Set[Id[User]]) = (users intersect f)
  }
}
