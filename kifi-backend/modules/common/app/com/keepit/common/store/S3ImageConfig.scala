package com.keepit.common.store

case class S3ImageConfig(val bucketName: String, val cdnBase: String, isLocal: Boolean = false)
