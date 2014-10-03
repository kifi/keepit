package com.keepit.search.engine.result;

public final class Hit {
  // This class is defined in Java so that we can avoid the accessor methods
  public long id;
  public float score;
  public float normalizedScore;
  public int visibility;
  public long secondaryId;
}
