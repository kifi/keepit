package com.keepit.search

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
  case object Invalid extends LibraryContext {
    def get: Long = throw new NoSuchElementException("invalid library context")
  }
}
