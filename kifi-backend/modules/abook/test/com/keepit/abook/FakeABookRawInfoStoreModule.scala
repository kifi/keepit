package com.keepit.abook

import com.keepit.common.store.StoreModule
import com.keepit.model._
import scala.collection.mutable.HashMap
import com.keepit.abook.store.ABookRawInfoStore

case class FakeABookRawInfoStoreModule() extends StoreModule {
  def configure():Unit = {
    bind[ABookRawInfoStore].toInstance(new HashMap[String, ABookRawInfo] with ABookRawInfoStore)
  }

}