package com.keepit.common.store

import com.keepit.inject.AppScoped

case class ShoeboxS3Module() extends S3Module {
  def configure() {
    bind[ImageDataIntegrityPlugin].to[ImageDataIntegrityPluginImpl].in[AppScoped]
  }
}
