package com.keepit.search.query

import org.specs2.mutable.Specification
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.util.Version


class QueryUtilTest extends Specification{
  "QueryUtil" should {

    "correctly extract term offsets" in {
      val queryText = "this is a (critical) test that should not fail"
      QueryUtil.getTermOffsets(new StandardAnalyzer(Version.LUCENE_42), queryText) === List((11,19), (21,25), (31,37), (42,46))
      QueryUtil.getTermOffsets(new StandardAnalyzer(Version.LUCENE_42), "") === List.empty[(Int, Int)]
    }

  }
}
