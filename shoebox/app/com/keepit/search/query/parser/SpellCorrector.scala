package com.keepit.search.query.parser

import org.apache.lucene.search
import org.apache.lucene.store.FSDirectory
import java.io.File
import org.apache.lucene.search.spell.SpellChecker
import org.apache.lucene.search.spell.PlainTextDictionary
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.search.spell.LevensteinDistance
import org.apache.lucene.search.spell.NGramDistance
import org.apache.lucene.store.Directory
import org.apache.lucene.search.spell.LuceneDictionary
import org.apache.lucene.index.DirectoryReader
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.common.logging.Logging
import com.google.inject.{Singleton}


trait SpellCorrector {
  def getAlternativeQuery(input: String): String
  def buildDictionary()
  def getBuildingStatus(): Boolean
}

object SpellCorrector {
  def apply(spellIndexDirectory: Directory, articleIndexDirectory: Directory) = {
    val analyzer = DefaultAnalyzer.forIndexing
    val config = new IndexWriterConfig(Version.LUCENE_42, analyzer)
    new SpellCorrectorImpl(spellIndexDirectory, articleIndexDirectory, config)
  }
}

class SpellCorrectorImpl(spellIndexDirectory: Directory, articleIndexDirectory: Directory, config: IndexWriterConfig) extends SpellCorrector with Logging {
  val spellChecker = new SpellChecker(spellIndexDirectory)
  var isBuilding = false
  def buildDictionary() = {
    if ( !isBuilding ) {
      val reader = DirectoryReader.open(articleIndexDirectory)
      try {
        log.info("spell-checker is building dictionary ... ")
        isBuilding = true
        spellChecker.indexDictionary(new LuceneDictionary(reader, "c"), config, false) // fullMerge = false
      } finally {
        reader.close()
        isBuilding = false
      }
    }
  }

  def getBuildingStatus = isBuilding

  def getAlternativeQuery(queryText: String) = {
    val terms = queryText.split(" ")
    terms.map(t => {
      if (spellChecker.exist(t)) t
      else {
        val suggest = getSimilarTerm(t)
        if (suggest.size == 0) t else suggest(0)
      }
    }).toList.mkString(" ")
  }

  // TODO: return more suggestions. Choose best suggestion based on surrounding text
  def getSimilarTerm(termText: String) = {
    spellChecker.suggestSimilar(termText, 1)
  }
}

class FakeSpellCorrector() extends SpellCorrector {
  def getAlternativeQuery(input: String) = input
  def buildDictionary() = {}
  def getBuildingStatus = false
}


















