package com.keepit.search.spellcheck

import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.MultiReader
import org.apache.lucene.search.spell.{ SpellChecker, HighFrequencyDictionary, LevensteinDistance, NGramDistance, StringDistance }
import org.apache.lucene.store.Directory
import org.apache.lucene.util.Version
import com.keepit.common.logging.Logging
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.sharding.ShardedArticleIndexer

case class SpellCheckerConfig(
  wordFreqThreshold: Float = 0.003f,
  distance: String = "composite")

object SpellCheckerFactory {
  def apply(spellIndexDirectory: Directory, config: SpellCheckerConfig) = {
    config.distance match {
      case "composite" => new SpellChecker(spellIndexDirectory, new CompositeDistance())
      case "ngram" => new SpellChecker(spellIndexDirectory, new NGramDistance())
      case "lev" => new SpellChecker(spellIndexDirectory, new LevensteinDistance())
      case _ => new SpellChecker(spellIndexDirectory, new LevensteinDistance())
    }
  }
}

trait SpellIndexer {
  def buildDictionary(): Unit
  def getSpellChecker(): SpellChecker
  def getTermStatsReader(): TermStatsReader
  protected def getIndexReader(): IndexReader
}

object SpellIndexer {
  def apply(spellIndexDirectory: Directory, shardedArticleIndexer: ShardedArticleIndexer, spellConfig: SpellCheckerConfig = SpellCheckerConfig()) = {

    new SpellIndexerImpl(spellIndexDirectory, spellConfig) {
      protected def getIndexReader(): IndexReader = {
        val readers = shardedArticleIndexer.indexShards.values.map(_.getSearcher.indexReader.asInstanceOf[IndexReader]).toArray
        if (readers.length == 1) readers(0) else new MultiReader(readers, false)
      }
    }
  }
}

abstract class SpellIndexerImpl(
    spellIndexDirectory: Directory,
    spellConfig: SpellCheckerConfig) extends SpellIndexer with Logging {

  var spellChecker = createChecker()

  override def getSpellChecker(): SpellChecker = spellChecker
  private def createChecker() = SpellCheckerFactory(spellIndexDirectory, spellConfig)
  private def refreshSpellChecker = { spellChecker = createChecker() }

  override def getTermStatsReader() = new TermStatsReaderImpl(getIndexReader(), "c")

  override def buildDictionary() = {

    try {
      log.info("spell-checker is building dictionary ... ")
      val t1 = System.currentTimeMillis
      val dict = new HighFrequencyDictionary(getIndexReader(), "c", spellConfig.wordFreqThreshold)
      val config = new IndexWriterConfig(Version.LUCENE_47, DefaultAnalyzer.defaultAnalyzer)
      spellChecker.indexDictionary(dict, config, true) // fullMerge = true
      val t2 = System.currentTimeMillis
      log.info(s"spell-checker has built the dictionary ... Time elapsed: ${(t2 - t1) / 1000.0} seconds")
    } finally {
      refreshSpellChecker
    }
  }

}

