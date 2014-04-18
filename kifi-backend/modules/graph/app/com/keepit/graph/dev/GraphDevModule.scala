package com.keepit.dev

import com.keepit.inject.CommonDevModule
import com.keepit.graph.GraphModule
import com.keepit.graph.simple.SimpleGraphDevModule
import com.keepit.graph.common.store.GraphDevStoreModule

case class GraphDevModule() extends GraphModule(GraphDevStoreModule(), SimpleGraphDevModule()) with CommonDevModule
