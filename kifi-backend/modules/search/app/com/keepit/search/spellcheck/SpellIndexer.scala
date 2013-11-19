package com.keepit.search.spellcheck

import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.search.spell.{SpellChecker, HighFrequencyDictionary, LevensteinDistance, NGramDistance, StringDistance}
import org.apache.lucene.store.Directory
import org.apache.lucene.util.Version

import com.keepit.common.logging.Logging
import com.keepit.search.index.DefaultAnalyzer

case class SpellCheckerConfig(
  wordFreqThreshold: Float = 0.001f,
  distance: String = "ngram"
)

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
}

object SpellIndexer {
  def apply(spellIndexDirectory: Directory, articleIndexDirectory: Directory, spellConfig: SpellCheckerConfig = SpellCheckerConfig()) = {
    val analyzer = DefaultAnalyzer.forIndexing
    val config = new IndexWriterConfig(Version.LUCENE_41, analyzer)
    new SpellIndexerImpl(spellIndexDirectory, articleIndexDirectory, config, spellConfig)
  }
}

class SpellIndexerImpl(
  spellIndexDirectory: Directory,
  articleIndexDirectory: Directory,
  config: IndexWriterConfig,
  spellConfig: SpellCheckerConfig
) extends SpellIndexer with Logging{

  val termStatsReader = new TermStatsReaderImpl(articleIndexDirectory, "c")
  var spellChecker = createChecker()

  override def getSpellChecker(): SpellChecker = spellChecker
  private  def createChecker() =  SpellCheckerFactory(spellIndexDirectory, spellConfig)
  private def refreshSpellChecker = { spellChecker = createChecker() }

  override def getTermStatsReader() = termStatsReader

  override def buildDictionary() = {

    val reader = DirectoryReader.open(articleIndexDirectory)
    try {
      log.info("spell-checker is building dictionary ... ")
      val t1 = System.currentTimeMillis
      val dict = new HighFrequencyDictionary(reader, "c", spellConfig.wordFreqThreshold)
      spellChecker.indexDictionary(dict, config, true) // fullMerge = true
      val t2 = System.currentTimeMillis
      log.info(s"spell-checker has built the dictionary ... Time elapsed: ${(t2 - t1)/1000.0 } seconds")
    } finally {
      reader.close()
      refreshSpellChecker
    }
  }

}
