package com.keepit

import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.cortex.models.word2vec.Word2Vec

package object cortex {

  val S3_CORTEX_BUCKET = "amazon.s3.cortex.bucket"

  object ModelVersions {
    val denseLDAVersion = ModelVersion[DenseLDA](2)
    val word2vecVersion = ModelVersion[Word2Vec](2)
  }

  object ModelStorePrefix {
    val denseLDA = "stat_models/dense_lda/"
    val word2vec = "stat_models/word2vec/"
  }

  object FeatureStorePrefix {

    object URIFeature {
      val denseLDA = "features/uri/dense_lda/"
      val word2vec = "features/uri/word2vec/"
    }

  }

  object CommitInfoStorePrefix {

    object URIFeature {
      val denseLDA = "commit_info/uri_features/dense_lda/"
      val word2vec = "commit_info/uri_features/word2vec/"
    }

  }

  object MiscPrefix {
    object LDA {
      val topicWordsFolder = "misc/lda/topic_words/"
      val topicConfigsFolder = "misc/lda/topic_configs/"
      val topicWordsJsonFile = "topic_words"
      val topicConfigsJsonFile = "topic_configs"
      val userLDAStatsFolder = "misc/lda/user_lda_stats/"
      val userLDAStatsJsonFile = "user_lda_stats"
    }
    object Stopwords {
      val stopwordsFoler = "misc/stopwords/"
      val stopwordsJsonFile = "stopwords"
    }
  }

  object PublishedModels {
    val denseLDAVersion = ModelVersion[DenseLDA](2) // doesn't have to sync with cortex lda version
    val defaultSparsity = 5
  }

}
