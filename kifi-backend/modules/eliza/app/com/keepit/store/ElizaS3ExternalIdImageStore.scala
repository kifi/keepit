package com.keepit.store

import com.google.inject.Inject
import com.keepit.common.store.{S3ImageConfig, S3ExternalIdImageStore}

class ElizaS3ExternalIdImageStore @Inject() (
  val config: S3ImageConfig
) extends S3ExternalIdImageStore {

}
