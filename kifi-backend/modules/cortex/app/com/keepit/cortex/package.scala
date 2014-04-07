package com.keepit

import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.DenseLDA


package object cortex {

  object ModelVersions {
    val denseLDAVersion = ModelVersion[DenseLDA](1)
  }

  object ModelStorePrefix {
    val denseLDA = "stat_models/dense_lda/"
  }

  object FeatureStorePrefix {

    object URIFeature {
      val denseLDA = "uri_features/dense_lda/"
    }

  }

  object CommitInfoStorePrefix {

    object URIFeature {
      val denseLDA = "commit_info/uri_features/dense_lda/"
    }

  }

}
