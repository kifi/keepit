package com.keepit.search.engine

import com.keepit.common.db.Id
import com.keepit.search.engine.parser.KQueryExpansion
import com.keepit.search.engine.result.ResultCollector
import com.keepit.search.query.parser.{ DefaultSyntax, QueryParser }
import com.keepit.search.util.join.{ DataBufferWriter, DataBuffer }
import com.keepit.search.{ Searcher, Lang, Tst, TstIndexer }
import com.keepit.search.index.{ WrappedSubReader, DefaultAnalyzer, VolatileIndexDirectory }
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.search.{ DocIdSetIterator, Scorer }
import org.specs2.mutable.Specification

class QueryEngineTest extends Specification {

  class TstScoreVectorSource(indexer: TstIndexer) extends ScoreVectorSourceLike {

    protected val searcher: Searcher = indexer.getSearcher

    protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], coreSize: Int, output: DataBuffer): Unit = {
      val reader = readerContext.reader.asInstanceOf[WrappedSubReader]

      val pq = createScorerQueue(scorers, coreSize)
      if (pq.size <= 0) return // no scorer

      val idMapper = reader.getIdMapper
      val writer: DataBufferWriter = new DataBufferWriter

      val taggedScores: Array[Int] = new Array[Int](pq.size) // tagged floats

      var docId = pq.top.doc
      while (docId < DocIdSetIterator.NO_MORE_DOCS) {
        val id = idMapper.getId(docId)

        // get all scores
        val size = pq.getTaggedScores(taggedScores)

        // write to the buffer
        output.alloc(writer, Visibility.OTHERS, 8 + size * 4) // id (8 bytes) and taggedFloats (size * 4 bytes)
        writer.putLong(id).putTaggedFloatBits(taggedScores, size)
        docId = pq.top.doc // next doc
      }
    }
  }

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

  private val indexer2 = new TstIndexer(new VolatileIndexDirectory)
  Array("def", "", "", "def", "", "", "def", "", "", "def").zipWithIndex.foreach {
    case (text, id) => indexer2.index(Id[Tst](id), text, "")
  }

  "QueryEngine" should {
    "find matches with index1" in {
      val query = getParser().parse("abc def").get
      val collector = new TstResultCollector
      val engine = new QueryEngineBuilder(query).build

      engine.execute(new TstScoreVectorSource(indexer1))
      engine.join(collector)

      collector.hits === Set(0, 2, 4, 6, 8)

    }

    "find matches with index2" in {
      val query = getParser().parse("abc def").get
      val collector = new TstResultCollector
      val engine = new QueryEngineBuilder(query).build

      engine.execute(new TstScoreVectorSource(indexer2))
      engine.join(collector)

      collector.hits === Set(0, 3, 6, 9)
    }

    "find matches with index1 and index2 using Disjunction" in {
      val query = getParser().parse("abc def").get
      val collector = new TstResultCollector
      val engine = new QueryEngineBuilder(query).build

      engine.execute(new TstScoreVectorSource(indexer1))
      engine.execute(new TstScoreVectorSource(indexer2))
      engine.join(collector)

      collector.hits === Set(0, 2, 3, 4, 6, 8, 9)
    }

    "find matches with index1 and index2 using Conjunction" in {
      val query = getParser().parse("+abc +def").get
      val collector = new TstResultCollector
      val engine = new QueryEngineBuilder(query).build

      engine.execute(new TstScoreVectorSource(indexer1))
      engine.execute(new TstScoreVectorSource(indexer2))
      engine.join(collector)

      collector.hits === Set(0, 6)
    }

    "normalize match weights" in {
      val query = getParser().parse("abc def").get
      val collector = new TstResultCollector
      val engine = new QueryEngineBuilder(query).build

      engine.execute(new TstScoreVectorSource(indexer1))
      engine.execute(new TstScoreVectorSource(indexer2))
      engine.join(collector)

      engine.getMatchWeights().sum === 1.0f
    }
  }
}
