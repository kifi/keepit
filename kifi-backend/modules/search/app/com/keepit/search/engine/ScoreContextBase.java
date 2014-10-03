package com.keepit.search.engine;

import com.keepit.search.util.join.Joiner;

abstract public class ScoreContextBase extends Joiner {
  // This class is defined so that we can avoid the accessor method generation by Scala compiler for fields below.
  public int visibility = 0;
  public long secondaryId = -1; // secondary id (keep id for kifi search)
  public int degree = 0;
  public final float[] scoreMax;
  public final float[] scoreSum;

  protected ScoreContextBase(float[] scoreMaxArray, float[] scoreSumArray) {
    scoreMax = scoreMaxArray;
    scoreSum = scoreSumArray;
  }
}
