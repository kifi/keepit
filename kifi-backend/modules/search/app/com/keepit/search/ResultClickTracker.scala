package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.model.{User, NormalizedURI}
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.query.QueryHash
import scala.math._
import java.io.File
import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provides, Singleton}
import play.api.Play._
import com.keepit.model.NormalizedURI
import com.keepit.model.User



abstract class ResultClickBoosts {
  def apply(value: Long): Float
}

class ResultClickTracker(lru: ProbablisticLRU) {
  
  private[this] val analyzer = DefaultAnalyzer.defaultAnalyzer

  def add(userId: Id[User], query: String, uriId: Id[NormalizedURI], rank: Int, isUserKeep: Boolean) = {
    val hash = QueryHash(userId, query, analyzer)
    val updateStrength = if (isUserKeep) {
      min(0.1d * (rank.toDouble + 3.0d), 0.7d)
    } else {
      0.20d
    }
    lru.put(hash, uriId.id, updateStrength)
  }

  def getBoosts(userId: Id[User], query: String, maxBoost: Float) = {
    val hash = QueryHash(userId, query, analyzer)
    val likeliness = lru.get(hash)
    new ResultClickBoosts {
      def apply(value: Long) = 1.0f +  (maxBoost - 1.0f) * likeliness(value)
    }
  }
}

trait ResultFeedbackModule extends ScalaModule

case class ResultFeedbackImplModule() extends ResultFeedbackModule {

  def configure() {}

  @Singleton
  @Provides
  def resultClickTracker(s3buffer: S3BackedResultClickTrackerBuffer): ResultClickTracker = {
    val conf = current.configuration.getConfig("result-click-tracker").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val syncEvery = conf.getInt("syncEvery").get
    val dirPath = conf.getString("dir").get
    val dir = new File(dirPath).getCanonicalFile()
    if (!dir.exists()) {
      if (!dir.mkdirs()) {
        throw new Exception(s"could not create dir $dir")
      }
    }
    val file = new File(dir, "resultclicks.plru")
    // table size = 16M (physical size = 64MB + 4bytes)
    val buffer = new MultiplexingBuffer(new FileResultClickTrackerBuffer(file, 0x1000000), s3buffer)
    new ResultClickTracker(new ProbablisticLRU(buffer, numHashFuncs, syncEvery))
  }

}
