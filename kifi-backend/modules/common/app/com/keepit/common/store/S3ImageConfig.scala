package com.keepit.common.store

case class S3ImageConfig(bucketName: String, cdnBase: String, isLocal: Boolean = false)
