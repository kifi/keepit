package com.keepit.abook

import com.keepit.common.store.StoreModule
import com.keepit.abook.store.{ InMemoryABookRawInfoStoreImpl, ABookRawInfoStore }
import com.google.inject.{ Provides, Singleton }
import com.keepit.abook.typeahead.{ InMemoryEContactTypeaheadStore, EContactTypeaheadStore }

case class FakeABookStoreModule() extends StoreModule {
  def configure(): Unit = {}

  @Provides @Singleton
  def aBookRawInfoStoreStore(): ABookRawInfoStore = new InMemoryABookRawInfoStoreImpl()

  @Singleton
  @Provides
  def econtactTypeaheadStore(): EContactTypeaheadStore = new InMemoryEContactTypeaheadStore()

}