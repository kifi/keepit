package com.keepit.search.spellcheck

import org.apache.lucene.store.Directory
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.search.spell.SpellChecker
import com.keepit.common.logging.Logging
import org.apache.lucene.search.spell.HighFrequencyDictionary
import org.apache.lucene.index.DirectoryReader
import com.keepit.search.index.DefaultAnalyzer
import org.apache.lucene.util.Version

trait SpellIndexer {
  def buildDictionary(): Unit
}

object SpellIndexer {
  def apply(spellIndexDirectory: Directory, articleIndexDirectory: Directory) = {
    val analyzer = DefaultAnalyzer.forIndexing
    val config = new IndexWriterConfig(Version.LUCENE_42, analyzer)
    new SpellIndexerImpl(spellIndexDirectory, articleIndexDirectory, config)
  }
}

class SpellIndexerImpl(
  spellIndexDirectory: Directory,
  articleIndexDirectory: Directory,
  config: IndexWriterConfig
) extends SpellIndexer with Logging{

  val spellChecker = new SpellChecker(spellIndexDirectory)
  val threshold = 0.001f

  def buildDictionary() = {

    val reader = DirectoryReader.open(articleIndexDirectory)
    try {
      log.info("spell-checker is building dictionary ... ")
      spellChecker.indexDictionary(new HighFrequencyDictionary(reader, "c", threshold), config, false) // fullMerge = false
      log.info("spell-checker has built the dictionary ... ")
    } finally {
      reader.close()
    }
  }
}
