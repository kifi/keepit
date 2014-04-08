package com.keepit

import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.DenseLDA


package object cortex {

  val S3_CORTEX_BUCKET = "amazon.s3.cortex.bucket"

  object ModelVersions {
    val denseLDAVersion = ModelVersion[DenseLDA](1)
  }

  object ModelStorePrefix {
    val denseLDA = "stat_models/dense_lda/"
  }

  object FeatureStorePrefix {

    object URIFeature {
      val denseLDA = "features/uri/dense_lda/"
    }

  }

  object CommitInfoStorePrefix {

    object URIFeature {
      val denseLDA = "commit_info/uri_features/dense_lda/"
    }

  }

}
