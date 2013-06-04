package com.keepit.common.store

import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.inject._
import play.api.Play
import play.api.Play.current
import java.io.InputStream
import java.io.ByteArrayInputStream
import scala.collection.mutable.HashMap

trait InMemoryObjectStore[A, B]  extends ObjectStore[A, B] with Logging {

  if (Play.isProd) throw new Exception("Can't have in memory object store in production")

  protected val localStore = new HashMap[A, B]()

  def += (kv: (A, B)) = {
    localStore += kv
    this
  }

  def -= (key: A) = {
    localStore -= key
    this
  }

  def get(id: A): Option[B] = localStore.get(id)
}
