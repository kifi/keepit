package com.keepit.search.engine.query.core

import org.apache.lucene.search.DisjunctionMaxQuery

class KProximityQuery extends DisjunctionMaxQuery(0.0f)
