package com.keepit

import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.cortex.models.word2vec.Word2Vec


package object cortex {

  val S3_CORTEX_BUCKET = "amazon.s3.cortex.bucket"

  object ModelVersions {
    val denseLDAVersion = ModelVersion[DenseLDA](1)
    val word2vecVersion = ModelVersion[Word2Vec](1)
  }

  object ModelStorePrefix {
    val denseLDA = "stat_models/dense_lda/"
    val word2vec = "stat_models/word2vec/"
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
