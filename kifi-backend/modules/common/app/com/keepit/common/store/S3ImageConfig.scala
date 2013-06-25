package com.keepit.common.store

import com.keepit.common.db.ExternalId
import com.keepit.model.User
import com.keepit.common.net.URI

case class S3ImageConfig(val bucketName: String, val cdnBase: String, isLocal: Boolean = false)
