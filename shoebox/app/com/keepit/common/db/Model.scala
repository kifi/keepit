package com.keepit.common.db

trait Model[M] {
  def withId(id: Id[M]): M
}
