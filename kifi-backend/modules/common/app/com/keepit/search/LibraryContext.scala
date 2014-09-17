package com.keepit.search

import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.model.Library

sealed trait LibraryContext {
  def get: Long
}

object LibraryContext {
  case class Authorized(id: Long) extends LibraryContext {
    def get: Long = id
  }
  case class NotAuthorized(id: Long) extends LibraryContext {
    def get: Long = id
  }
  case object None extends LibraryContext {
    def get: Long = throw new NoSuchElementException("no library context")
  }
}
