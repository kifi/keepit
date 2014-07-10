package com.keepit.abook

import com.keepit.common.store.StoreModule
import com.keepit.model._
import scala.collection.mutable.HashMap
import com.keepit.abook.store.ABookRawInfoStore
import com.google.inject.{ Provides, Singleton }
import com.keepit.abook.typeahead.{ InMemoryEContactTypeaheadStore, EContactTypeaheadStore }

case class FakeABookStoreModule() extends StoreModule {
  def configure(): Unit = {
    bind[ABookRawInfoStore].toInstance(new HashMap[String, ABookRawInfo] with ABookRawInfoStore)
  }

  @Singleton
  @Provides
  def econtactTypeaheadStore(): EContactTypeaheadStore = new InMemoryEContactTypeaheadStore()

}