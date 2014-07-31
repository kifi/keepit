package com.keepit.search.engine

import com.keepit.common.db.Id
import com.keepit.search.engine.parser.KQueryExpansion
import com.keepit.search.engine.result.{ ResultCollector }
import com.keepit.search.query.parser.{ DefaultSyntax, QueryParser }
import com.keepit.search.util.LongArraySet
import com.keepit.search.{ Lang, Tst, TstIndexer }
import com.keepit.search.index.{ DefaultAnalyzer, VolatileIndexDirectory }
import org.specs2.mutable.Specification

class QueryEngineTest extends Specification {

  private def getParser(): QueryParser = {
    val english = Lang("en")
    val analyzer = DefaultAnalyzer.getAnalyzer(english)
    val stemmingAnalyzer = DefaultAnalyzer.getAnalyzerWithStemmer(english)
    new QueryParser(analyzer, stemmingAnalyzer) with DefaultSyntax with KQueryExpansion {
      override val lang = english
      override val altAnalyzer = None
      override val altStemmingAnalyzer = None
      override val siteBoost: Float = 1.0f
      override val concatBoost: Float = 0.5f
    }
  }

  private class TstResultCollector extends ResultCollector[ScoreContext] {
    var hits = Set.empty[Long]

    def collect(ctx: ScoreContext): Unit = {
      if (ctx.score() > 0.0f) hits += ctx.id
    }
  }

  private val indexer1 = new TstIndexer(new VolatileIndexDirectory)
  Array("abc", "", "abc", "", "abc", "", "abc", "", "abc", "").zipWithIndex.foreach {
    case (text, id) => indexer1.index(Id[Tst](id), text, "")
  }
  private val searcher1 = indexer1.getSearcher

  private val indexer2 = new TstIndexer(new VolatileIndexDirectory)
  Array("def", "", "", "def", "", "", "def", "", "", "def").zipWithIndex.foreach {
    case (text, id) => indexer2.index(Id[Tst](id), text, "")
  }
  private val searcher2 = indexer2.getSearcher

  "QueryEngine" should {
    "find matches with index1" in {
      val query = getParser().parse("abc def").get
      val collector = new TstResultCollector
      val builder = new QueryEngineBuilder(query, -1.0f).setResultCollector(collector)
      val engine = builder.build

      engine.execute(searcher1) { (reader, scorers) => new ArticleScoreVectorSource(reader, scorers, LongArraySet.empty) }
      engine.join()

      collector.hits === Set(0, 2, 4, 6, 8)

    }

    "find matches with index2" in {
      val query = getParser().parse("abc def").get
      val collector = new TstResultCollector
      val builder = new QueryEngineBuilder(query, -1.0f).setResultCollector(collector)
      val engine = builder.build

      engine.execute(searcher2) { (reader, scorers) => new ArticleScoreVectorSource(reader, scorers, LongArraySet.empty) }
      engine.join()

      collector.hits === Set(0, 3, 6, 9)
    }

    "find matches with index1 and index2 using Disjunction" in {
      val query = getParser().parse("abc def").get
      val collector = new TstResultCollector
      val builder = new QueryEngineBuilder(query, -1.0f).setResultCollector(collector)
      val engine = builder.build

      engine.execute(searcher1) { (reader, scorers) => new ArticleScoreVectorSource(reader, scorers, LongArraySet.empty) }
      engine.execute(searcher2) { (reader, scorers) => new ArticleScoreVectorSource(reader, scorers, LongArraySet.empty) }
      engine.join()

      collector.hits === Set(0, 2, 3, 4, 6, 8, 9)
    }

    "find matches with index1 and index2 using Conjunction" in {
      val query = getParser().parse("+abc +def").get
      val collector = new TstResultCollector
      val builder = new QueryEngineBuilder(query, -1.0f).setResultCollector(collector)
      val engine = builder.build

      engine.execute(searcher1) { (reader, scorers) => new ArticleScoreVectorSource(reader, scorers, LongArraySet.empty) }
      engine.execute(searcher2) { (reader, scorers) => new ArticleScoreVectorSource(reader, scorers, LongArraySet.empty) }
      engine.join()

      collector.hits === Set(0, 6)
    }
  }
}
