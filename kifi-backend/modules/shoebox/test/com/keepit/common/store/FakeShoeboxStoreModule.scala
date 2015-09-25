package com.keepit.common.store

import com.google.inject.{ Singleton, Provides }
import com.keepit.common.db.slick.Database
import com.keepit.model.UserRepo
import com.keepit.social.{ SocialUserRawInfoStore, InMemorySocialUserRawInfoStoreImpl }
import com.keepit.typeahead.{ InMemoryKifiUserTypeaheadStoreImpl, KifiUserTypeaheadStore, InMemorySocialUserTypeaheadStoreImpl, SocialUserTypeaheadStore }

case class FakeShoeboxStoreModule() extends FakeStoreModule {

  @Provides @Singleton
  def s3ImageStore(s3ImageConfig: S3ImageConfig, db: Database, userRepo: UserRepo): S3ImageStore = FakeS3ImageStore(s3ImageConfig, db, userRepo)

  @Provides @Singleton
  def socialUserRawInfoStore(): SocialUserRawInfoStore = new InMemorySocialUserRawInfoStoreImpl()

  @Provides @Singleton
  def kifiInstallationStore(): KifiInstallationStore = {
    new InMemoryKifiInstallationStoreImpl()
  }

  @Provides @Singleton
  def socialUserTypeaheadStore(): SocialUserTypeaheadStore = {
    new InMemorySocialUserTypeaheadStoreImpl()
  }

  @Provides @Singleton
  def kifiUserTypeaheadStore(): KifiUserTypeaheadStore = {
    new InMemoryKifiUserTypeaheadStoreImpl()
  }
}
